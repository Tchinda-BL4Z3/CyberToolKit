//! Cible WebAssembly : le **même moteur**, exposé par une ABI C minimale.
//!
//! Pas de `wasm-bindgen` : le navigateur alloue, écrit, appelle, lit, libère.
//! Le format d'échange est JSON pour rester lisible et testable.

use std::alloc::{alloc, dealloc, Layout};

use sensitive_core::engine::{self, ScanOptions};
use sensitive_core::{Category, Confidence, RuleSet};
use serde::{Deserialize, Serialize};
use zeroize::Zeroize;

/// Requête envoyée par le worker.
#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ScanRequest {
    #[serde(default)]
    text: String,
    #[serde(default)]
    min_level: Option<String>,
    #[serde(default)]
    categories: Vec<String>,
}

#[derive(Debug, Serialize)]
struct ErrorReport<'a> {
    error: &'a str,
    message: String,
}

/// Alloue `len` octets côté Rust, lisibles et inscripts par le JS.
///
/// # Sécurité
/// `len` vient du JS : une valeur déraisonnable échoue à l'allocation plutôt
/// que de provoquer une écriture hors périmètre.
#[no_mangle]
pub extern "C" fn pss_alloc(len: usize) -> *mut u8 {
    if len == 0 {
        return std::ptr::null_mut();
    }
    match Layout::from_size_align(len, 1) {
        Ok(layout) => unsafe { alloc(layout) },
        Err(_) => std::ptr::null_mut(),
    }
}

/// Efface puis libère un bloc obtenu par `pss_alloc` (F-76).
///
/// Le tampon est mis à zéro avant sa libération : le document analysé et le
/// rapport JSON ne restent pas dans la mémoire du module après usage.
/// Pointeur ou longueur nuls tolérés.
///
/// # Safety
/// Le pointeur doit provenir de `pss_alloc` avec la même longueur : tout
/// autre couple (offset fourni par le JS, longueur modifiée) est un code
/// invalide et se comporte de façon indéfini, comme tout ABI C.
#[no_mangle]
pub unsafe extern "C" fn pss_dealloc(ptr: *mut u8, len: usize) {
    if ptr.is_null() || len == 0 {
        return;
    }
    wipe(std::slice::from_raw_parts_mut(ptr, len));
    if let Ok(layout) = Layout::from_size_align(len, 1) {
        dealloc(ptr, layout);
    }
}

/// Écrit des zéros par-dessus un tampon.
fn wipe(buffer: &mut [u8]) {
    buffer.zeroize();
}

/// Analyse `len` octets UTF-8 à l'adresse `ptr`.
///
/// Le résultat est écrit dans les adresses fournies :
/// `out_ptr` reçoit un bloc à libérer via `pss_dealloc`,
/// `out_len` sa longueur en octets. Retourne `0` en cas de succès.
///
/// Deux pointeurs sortants plutôt qu'un entier packé : la largeur d'un
/// pointeur varie (4 octets en wasm32, 8 sur un poste 64 bits), et un
/// encodage implicite est la meilleure façon de faire lire de mauvais
/// octets au navigateur.
///
/// # Safety
/// `ptr`/`len` doivent décrire une zone lisible de `len` octets.
/// `out_ptr` et `out_len` doivent être des adresses valides en écriture
/// (par exemple `&mut` locaux), et ne pas recouvrir la zone d''entrée.
#[no_mangle]
pub unsafe extern "C" fn pss_scan(
    ptr: *const u8,
    len: usize,
    out_ptr: *mut *mut u8,
    out_len: *mut usize,
) -> i32 {
    let json = match build_report(ptr, len) {
        Ok(json) => json,
        Err(message) => serde_json::to_string(&ErrorReport {
            error: "invalid_request",
            message,
        })
        .unwrap_or_else(|_| String::from(r#"{"error":"invalid_request"}"#)),
    };
    write_result(&json, out_ptr, out_len)
}

/// Analyse un fichier bureautique DOCX/XLSX/PPTX (F-28).
///
/// `ptr`/`len` décrivent les octets de l'archive, `req_ptr`/`req_len` une
/// requête JSON d'options (`minLevel`, `categories`). Le rapport est précédé
/// du format détecté : `{"format":"docx","report":{...}}`.
///
/// # Safety
/// Mêmes exigences que `pss_scan` pour les quatre adresses ; les zones
/// d'entrée et de sortie ne doivent pas se recouvrir.
#[no_mangle]
pub unsafe extern "C" fn pss_scan_file(
    ptr: *const u8,
    len: usize,
    req_ptr: *const u8,
    req_len: usize,
    out_ptr: *mut *mut u8,
    out_len: *mut usize,
) -> i32 {
    let json = match build_file_report(ptr, len, req_ptr, req_len) {
        Ok(json) => json,
        Err(message) => serde_json::to_string(&ErrorReport {
            error: "invalid_request",
            message,
        })
        .unwrap_or_else(|_| String::from(r#"{"error":"invalid_request"}"#)),
    };
    write_result(&json, out_ptr, out_len)
}

fn write_result(json: &str, out_ptr: *mut *mut u8, out_len: *mut usize) -> i32 {
    let bytes = json.as_bytes();
    let out = pss_alloc(bytes.len());
    if out.is_null() {
        return 1;
    }
    unsafe {
        std::ptr::copy_nonoverlapping(bytes.as_ptr(), out, bytes.len());
        if !out_ptr.is_null() {
            *out_ptr = out;
        }
        if !out_len.is_null() {
            *out_len = bytes.len();
        }
    }
    0
}

fn parse_options(request: &ScanRequest) -> Result<ScanOptions, String> {
    let mut options = ScanOptions::all();
    if let Some(level) = &request.min_level {
        match Confidence::parse(level) {
            Some(l) => options.min_level = Some(l),
            None => return Err(format!("niveau de confiance inconnu : {level}")),
        }
    }
    for name in &request.categories {
        match Category::ALL
            .into_iter()
            .find(|c| c.as_str() == name.as_str())
        {
            Some(c) => options.categories.push(c),
            None => return Err(format!("catégorie inconnue : {name}")),
        }
    }
    Ok(options)
}

fn build_file_report(
    ptr: *const u8,
    len: usize,
    req_ptr: *const u8,
    req_len: usize,
) -> Result<String, String> {
    if (ptr.is_null() && len > 0) || (req_ptr.is_null() && req_len > 0) {
        return Err(String::from("pointeur d'entrée nul"));
    }
    let bytes = if len == 0 {
        &[][..]
    } else {
        unsafe { std::slice::from_raw_parts(ptr, len) }
    };
    let request: ScanRequest = if req_len == 0 {
        ScanRequest::default()
    } else {
        let req = unsafe { std::slice::from_raw_parts(req_ptr, req_len) };
        serde_json::from_slice(req).map_err(|e| format!("requête JSON invalide : {e}"))?
    };

    let extracted = sensitive_extract::extract(bytes).map_err(|e| e.to_string())?;
    let options = parse_options(&request)?;
    let rules = RuleSet::embedded();
    let report = engine::scan(&extracted.text, &rules, &options);
    let report_value: serde_json::Value =
        serde_json::from_str(&engine::report_to_json(&report)).map_err(|e| e.to_string())?;
    serde_json::to_string(&serde_json::json!({
        "format": extracted.format.as_str(),
        "report": report_value,
        "extractedText": extracted.text,
    }))
    .map_err(|e| e.to_string())
}

fn build_report(ptr: *const u8, len: usize) -> Result<String, String> {
    if ptr.is_null() && len > 0 {
        return Err(String::from("pointeur d'entrée nul"));
    }
    let input = if len == 0 {
        &[][..]
    } else {
        unsafe { std::slice::from_raw_parts(ptr, len) }
    };
    let request: ScanRequest =
        serde_json::from_slice(input).map_err(|e| format!("requête JSON invalide : {e}"))?;

    let options = parse_options(&request)?;
    let rules = RuleSet::embedded();
    let report = engine::scan(&request.text, &rules, &options);
    Ok(engine::report_to_json(&report))
}

#[cfg(test)]
mod tests {
    use super::*;

    use std::io::Write;

    fn call(request: &str) -> String {
        let bytes = request.as_bytes();
        let mut out_ptr: *mut u8 = std::ptr::null_mut();
        let mut out_len: usize = 0;
        let rc = unsafe { pss_scan(bytes.as_ptr(), bytes.len(), &mut out_ptr, &mut out_len) };
        assert_eq!(rc, 0, "pss_scan a échoué");
        assert!(!out_ptr.is_null());
        let slice = unsafe { std::slice::from_raw_parts(out_ptr, out_len) };
        let json = String::from_utf8(slice.to_vec()).unwrap();
        unsafe { pss_dealloc(out_ptr, out_len) };
        json
    }

    #[test]
    fn scan_via_abi_renvoie_des_findings() {
        let json = call(r#"{"text":"IBAN FR7600000000000000000000000"}"#);
        assert!(json.contains("fin.iban"), "{json}");
        assert!(!json.contains("error"), "{json}");
    }

    #[test]
    fn requete_invalide_renvoie_une_erreur_lisible() {
        let json = call("{pas du json");
        assert!(json.contains("invalid_request"), "{json}");
    }

    #[test]
    fn seuil_applique_via_abi() {
        let json = call(r#"{"text":"pierre.exemple@exemple.fr","minLevel":"certain"}"#);
        assert!(json.contains("\"total\":0"), "{json}");
    }

    #[test]
    fn categories_inconnues_signalees() {
        let json = call(r#"{"text":"x","categories":["peche"]}"#);
        assert!(json.contains("catégorie inconnue"), "{json}");
    }

    #[test]
    fn scan_file_docx_renvoie_le_format_et_le_rapport() {
        let mut writer = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
        let options = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Deflated);
        writer.start_file("word/document.xml", options).unwrap();
        writer
            .write_all(
                br#"<w:document xmlns:w="w"><w:body><w:p><w:t>IBAN FR7600000000000000000000000</w:t></w:p></w:body></w:document>"#,
            )
            .unwrap();
        let doc = writer.finish().unwrap().into_inner();

        let req = b"{}";
        let mut out_ptr: *mut u8 = std::ptr::null_mut();
        let mut out_len: usize = 0;
        let rc = unsafe {
            pss_scan_file(
                doc.as_ptr(),
                doc.len(),
                req.as_ptr(),
                req.len(),
                &mut out_ptr,
                &mut out_len,
            )
        };
        assert_eq!(rc, 0);
        let json = unsafe { std::slice::from_raw_parts(out_ptr, out_len) }.to_vec();
        let json = String::from_utf8(json).unwrap();
        assert!(json.contains("\"format\":\"docx\""), "{json}");
        assert!(json.contains("fin.iban"), "{json}");
        unsafe { pss_dealloc(out_ptr, out_len) };
    }

    #[test]
    fn scan_file_archive_inconnue_erreur_lisible() {
        let mut writer = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
        writer
            .start_file("lisezmoi.txt", zip::write::SimpleFileOptions::default())
            .unwrap();
        writer.write_all(b"bonjour").unwrap();
        let archive = writer.finish().unwrap().into_inner();

        let req = b"{}";
        let mut out_ptr: *mut u8 = std::ptr::null_mut();
        let mut out_len: usize = 0;
        let rc = unsafe {
            pss_scan_file(
                archive.as_ptr(),
                archive.len(),
                req.as_ptr(),
                req.len(),
                &mut out_ptr,
                &mut out_len,
            )
        };
        assert_eq!(rc, 0);
        let json = unsafe { std::slice::from_raw_parts(out_ptr, out_len) }.to_vec();
        let json = String::from_utf8(json).unwrap();
        assert!(json.contains("invalid_request"), "{json}");
        assert!(json.contains("Word"), "{json}");
        unsafe { pss_dealloc(out_ptr, out_len) };
    }

    #[test]
    fn alloc_et_dealloc_supportent_zero() {
        assert!(pss_alloc(0).is_null());
        unsafe { pss_dealloc(std::ptr::null_mut(), 0) };
    }

    #[test]
    fn wipe_efface_reellement_le_tampon() {
        let mut secret = *b"sk_live_51H8sSecretValue";
        wipe(&mut secret);
        assert!(secret.iter().all(|b| *b == 0), "{secret:?}");
    }

    #[test]
    fn le_tampon_alloue_est_efface_avant_sa_restitution() {
        let ptr = pss_alloc(8);
        assert!(!ptr.is_null());
        unsafe {
            std::ptr::copy_nonoverlapping(b"ABCD1234".as_ptr(), ptr, 8);
            wipe(std::slice::from_raw_parts_mut(ptr, 8));
            assert!(
                std::slice::from_raw_parts(ptr, 8).iter().all(|b| *b == 0),
                "le tampon contient encore des données"
            );
            pss_dealloc(ptr, 8);
        }
    }
}
