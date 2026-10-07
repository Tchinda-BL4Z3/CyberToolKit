//! Lecture d'octets en texte, avec détection d'encodage (F-27) et
//! extraction des formats bureautiques (F-28).
//!
//! Aucun réseau : `chardetng` travaille uniquement sur les octets fournis.

use chardetng::EncodingDetector;
use encoding_rs::Encoding;

use sensitive_extract::{extract, looks_like_zip};

/// Taille maximale d'un document analysable (F-31).
pub const MAX_INPUT_BYTES: usize = 50 * 1024 * 1024;

#[derive(Debug)]
pub struct Decoded {
    pub text: String,
    pub encoding: &'static str,
}

/// Découd un fichier : extraction bureautique si le nom l'indique, sinon
/// décodage d'encodage. Suffixe `.docx`, `.xlsx`, `.pptx` reconnu (F-28).
pub fn decode_named(bytes: &[u8], name: &str) -> Result<Decoded, String> {
    if matches!(
        extension_of(name),
        Some("docx") | Some("xlsx") | Some("pptx")
    ) {
        return match extract(bytes) {
            Ok(extracted) => Ok(Decoded {
                text: extracted.text,
                encoding: extracted.format.as_str(),
            }),
            Err(e) => Err(format!("{name} : {e}")),
        };
    }
    decode(bytes)
}

fn extension_of(name: &str) -> Option<&str> {
    let name = name.rsplit('/').next()?.rsplit('\\').next()?;
    let (_, ext) = name.rsplit_once('.')?;
    (!ext.is_empty()).then_some(ext)
}

/// Décodage : BOM d'abord, puis détection statistique, puis repli explicite.
pub fn decode(bytes: &[u8]) -> Result<Decoded, String> {
    if bytes.len() > MAX_INPUT_BYTES {
        return Err(format!(
            "document de {} octets : la limite est de {} octets (50 Mio)",
            bytes.len(),
            MAX_INPUT_BYTES
        ));
    }

    if looks_like_zip(bytes) {
        return Err(String::from(
            "archive ZIP non prise en charge : seuls les documents bureautiques              Word (.docx), Excel (.xlsx) et PowerPoint (.pptx) sont analysés",
        ));
    }

    if let Some(text) = decode_bom(bytes) {
        return Ok(Decoded {
            text,
            encoding: "bom",
        });
    }

    let mut detector = EncodingDetector::new();
    detector.feed(bytes, true);
    let encoding = detector.guess(None, true);
    if null_ratio(bytes) > 0.005 && encoding == Encoding::for_label(b"utf-8").unwrap() {
        return Err(String::from(
            "fichier binaire non pris en charge : seuls les textes et les documents              bureautiques (Word, Excel, PowerPoint) sont analysés",
        ));
    }
    let (text, _used, had_errors) = encoding.decode(bytes);
    if had_errors && encoding == Encoding::for_label(b"utf-8").unwrap() {
        // L'UTF-8 annoncé n'est pas valide : on le signale plutôt que de
        // produire du texte corrompu silencieusement.
        let lossy = String::from_utf8_lossy(bytes).into_owned();
        return Ok(Decoded {
            text: lossy,
            encoding: "utf-8 (remplacements)",
        });
    }
    Ok(Decoded {
        text: text.into_owned(),
        encoding: encoding.name(),
    })
}

/// Part des octets nuls dans le buffer, en `f32` entre 0 et 1.
fn null_ratio(bytes: &[u8]) -> f32 {
    if bytes.is_empty() {
        return 0.0;
    }
    let nul = bytes.iter().filter(|b| **b == 0).count();
    nul as f32 / bytes.len() as f32
}

fn decode_bom(bytes: &[u8]) -> Option<String> {
    if bytes.starts_with(&[0xEF, 0xBB, 0xBF]) {
        return String::from_utf8(bytes[3..].to_vec()).ok();
    }
    if bytes.starts_with(&[0xFF, 0xFE]) {
        let (text, _, _) = encoding_rs::UTF_16LE.decode(bytes);
        return Some(text.into_owned());
    }
    if bytes.starts_with(&[0xFE, 0xFF]) {
        let (text, _, _) = encoding_rs::UTF_16BE.decode(bytes);
        return Some(text.into_owned());
    }
    None
}

#[cfg(test)]
mod tests {
    use std::io::Write;

    use super::*;

    #[test]
    fn bom_utf8_est_reconnue() {
        let mut v = vec![0xEF, 0xBB, 0xBF];
        v.extend_from_slice("bonjour".as_bytes());
        let d = decode(&v).unwrap();
        assert_eq!(d.text, "bonjour");
        assert_eq!(d.encoding, "bom");
    }

    #[test]
    fn utf8_valide_sans_bom() {
        let d = decode("héllo".as_bytes()).unwrap();
        assert_eq!(d.text, "héllo");
        assert_eq!(d.encoding, "UTF-8");
    }

    #[test]
    fn decode_named_extrait_un_docx() {
        let mut writer = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
        let options = zip::write::SimpleFileOptions::default();
        writer
            .start_file(
                "word/document.xml",
                options.compression_method(zip::CompressionMethod::Deflated),
            )
            .unwrap();
        writer.write_all(
            br#"<w:document xmlns:w="w"><w:body><w:p><w:t>IBAN FR7600000000000000000000000</w:t></w:p></w:body></w:document>"#,
        ).unwrap();
        let bytes = writer.finish().unwrap().into_inner();
        let d = decode_named(&bytes, "contrat.docx").unwrap();
        assert_eq!(d.encoding, "docx");
        assert!(d.text.contains("FR7600000000000000000000000"));
    }

    #[test]
    fn decode_named_signale_un_zip_inconnu() {
        let mut writer = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
        writer
            .start_file("lisezmoi.txt", zip::write::SimpleFileOptions::default())
            .unwrap();
        writer.write_all(b"bonjour").unwrap();
        let bytes = writer.finish().unwrap().into_inner();
        assert!(decode_named(&bytes, "archive.zip").is_err());
    }

    #[test]
    fn un_binaire_est_signale_explicitement() {
        let bytes = [0u8, 1, 2, 3, 0, 4, 5, 0, 6];
        let err = decode(&bytes).unwrap_err();
        assert!(err.contains("binaire"), "{err}");
    }

    #[test]
    fn taille_max_respectee() {
        let mut big = vec![b'a'; MAX_INPUT_BYTES + 1];
        big.truncate(MAX_INPUT_BYTES + 1);
        assert!(decode(&big).unwrap_err().contains("limite"));
    }
}
