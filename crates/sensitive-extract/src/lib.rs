//! Extraction de texte depuis les formats bureautiques ZIP+XML (F-28).
//!
//! DOCX, XLSX et PPTX sont des archives ZIP contenant du XML : on lit le
//! XML pertinent (corps, en-têtes/pieds de page, cellules, diapositives) et
//! on rend un texte plat exploitable par le moteur. Aucun réseau, aucune
//! exécution de macro. Les limites de sécurité (F-31) protègent contre les
//! archives malveillantes : trop d'entrées, ratio de compression délirant,
//! volume décompressé excessif, ou archive chiffrée sont des erreurs.

pub mod zip_limits;

use std::io::Read;

use quick_xml::events::Event;
use quick_xml::Reader;

pub use crate::zip_limits::ZipLimits;

/// Budgets de sécurité appliqués à toute archive (F-31).
const LIMITS: ZipLimits = ZipLimits {
    max_entries: 5_000,
    max_uncompressed_total: 50 * 1024 * 1024,
    max_compression_ratio: 512,
};

const ZIP_MAGIC: [u8; 4] = *b"PK\x03\x04";
const EMPTY_ZIP_MAGIC: [u8; 4] = *b"PK\x05\x06";
const SPANNED_ZIP_MAGIC: [u8; 4] = *b"PK\x07\x08";

/// Format détecté dans une archive.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Format {
    /// Word 2007+ : `word/document.xml` + en-têtes/pieds de page.
    Docx,
    /// Excel 2007+ : `xl/sharedStrings.xml` + `xl/worksheets/*.xml`.
    Xlsx,
    /// PowerPoint 2007+ : `ppt/slides/*.xml`.
    Pptx,
}

impl Format {
    pub fn as_str(&self) -> &'static str {
        match self {
            Format::Docx => "docx",
            Format::Xlsx => "xlsx",
            Format::Pptx => "pptx",
        }
    }
}

/// Texte extrait et format identifié.
#[derive(Debug)]
pub struct Extracted {
    pub format: Format,
    pub text: String,
}

#[derive(Debug, PartialEq, Eq)]
pub enum ExtractError {
    /// Les octets ne ressemblent à aucune archive ZIP.
    NotAnArchive,
    /// Archive ZIP mais aucune des structures DOCX/XLSX/PPTX reconnue.
    UnknownStructure,
    /// Archive chiffrée (mot de passe requis côté client).
    Encrypted,
    /// Une des limites de sécurité (F-31) est dépassée.
    LimitExceeded(String),
    /// Le ZIP ou l'un des XML est illisible.
    InvalidArchive(String),
}

impl std::fmt::Display for ExtractError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ExtractError::NotAnArchive => write!(f, "ce n'est pas une archive ZIP"),
            ExtractError::UnknownStructure => write!(
                f,
                "archive ZIP reconnue mais ni Word, ni Excel, ni PowerPoint à l'intérieur"
            ),
            ExtractError::Encrypted => write!(f, "archive chiffrée : mot de passe requis"),
            ExtractError::LimitExceeded(detail) => write!(f, "archive refusée : {detail}"),
            ExtractError::InvalidArchive(detail) => write!(f, "archive invalide : {detail}"),
        }
    }
}

impl std::error::Error for ExtractError {}

/// Vrai si les octets commencent par la signature d'une archive ZIP.
pub fn looks_like_zip(bytes: &[u8]) -> bool {
    bytes.starts_with(&ZIP_MAGIC)
        || bytes.starts_with(&EMPTY_ZIP_MAGIC)
        || bytes.starts_with(&SPANNED_ZIP_MAGIC)
}

/// Extrait le texte d'une archive bureautique.
pub fn extract(bytes: &[u8]) -> Result<Extracted, ExtractError> {
    if !looks_like_zip(bytes) {
        return Err(ExtractError::NotAnArchive);
    }
    if bytes.len() as u64 > LIMITS.max_uncompressed_total as u64 {
        return Err(ExtractError::LimitExceeded(String::from(
            "archive trop volumineuse",
        )));
    }

    let mut archive = zip::ZipArchive::new(std::io::Cursor::new(bytes))
        .map_err(|e| ExtractError::InvalidArchive(e.to_string()))?;

    let mut entries = Vec::with_capacity(archive.len());
    let mut any_encrypted = false;
    let mut total_uncompressed = 0u64;

    for i in 0..archive.len() {
        let file = archive
            .by_index(i)
            .map_err(|e| ExtractError::InvalidArchive(e.to_string()))?;
        if file.encrypted() {
            any_encrypted = true;
        }
        let compressed = file.compressed_size();
        let uncompressed = file.size();
        total_uncompressed = total_uncompressed.saturating_add(uncompressed);
        if total_uncompressed > LIMITS.max_uncompressed_total as u64 {
            return Err(ExtractError::LimitExceeded(format!(
                "volume décompressé total de {total_uncompressed} octets au-delà de {} o",
                LIMITS.max_uncompressed_total
            )));
        }
        if uncompressed > 0
            && compressed > 0
            && uncompressed / compressed > LIMITS.max_compression_ratio as u64
        {
            return Err(ExtractError::LimitExceeded(format!(
                "entrée « {} » compressée au ratio {}:{}, au-delà de {}",
                file.name(),
                uncompressed,
                compressed,
                LIMITS.max_compression_ratio
            )));
        }
        if uncompressed > LIMITS.max_uncompressed_total as u64 {
            return Err(ExtractError::LimitExceeded(String::from(
                "une entrée décompressée dépasse la limite",
            )));
        }
        entries.push(file.name().to_string());
    }

    if entries.len() > LIMITS.max_entries {
        return Err(ExtractError::LimitExceeded(format!(
            "plus de {} entrées dans l'archive",
            LIMITS.max_entries
        )));
    }
    if any_encrypted && !entries.iter().any(|e| looks_unencrypted_companion(e)) {
        return Err(ExtractError::Encrypted);
    }

    let format = identify_format(entries.iter().map(String::as_str))
        .ok_or(ExtractError::UnknownStructure)?;

    let spec = XmlSpec::for_format(format);
    let mut budget = LIMITS.max_uncompressed_total;
    let mut pieces = Vec::new();
    for path in selected_paths(format, &entries) {
        match archive.by_name(&path) {
            Ok(file) => {
                let mut content = Vec::new();
                file.take(budget as u64)
                    .read_to_end(&mut content)
                    .map_err(|e| ExtractError::InvalidArchive(e.to_string()))?;
                budget = budget.saturating_sub(content.len());
                let text = collect_text(&content, &spec, budget)?;
                pieces.push(text);
            }
            Err(_) => continue,
        }
        if budget == 0 {
            return Err(ExtractError::LimitExceeded(String::from(
                "le texte extrait dépasse le volume autorisé",
            )));
        }
    }

    let mut text = pieces.join("\n");
    text = text.replace("\r\n", "\n").replace('\r', "\n");
    Ok(Extracted { format, text })
}

/// Certaines archives expriment un « compagnon » non chiffré même lorsque le
/// classeur réel est protégé : on ne bloque alors que sur la vraie absence de
/// contenu lisible. Ici on tolère un `[Content_Types].xml` non chiffré.
fn looks_unencrypted_companion(name: &str) -> bool {
    name == "[Content_Types].xml" || name == "_rels/.rels"
}

fn identify_format<'a>(mut names: impl Iterator<Item = &'a str>) -> Option<Format> {
    let mut docx = false;
    let mut xlsx = false;
    let mut pptx = false;
    for name in names.by_ref() {
        if name.starts_with("word/") {
            docx = true;
        } else if name.starts_with("xl/") {
            xlsx = true;
        } else if name.starts_with("ppt/") {
            pptx = true;
        }
    }
    if xlsx && !docx && !pptx {
        Some(Format::Xlsx)
    } else if pptx && !docx {
        Some(Format::Pptx)
    } else if docx {
        Some(Format::Docx)
    } else {
        None
    }
}

/// Balises qui portent du texte et balises de fin de paragraphe.
struct XmlSpec<'a> {
    text: &'a [&'a str],
    breaks: &'a [&'a str],
}

impl XmlSpec<'static> {
    fn for_format(format: Format) -> XmlSpec<'static> {
        match format {
            Format::Docx => XmlSpec {
                text: &["w:t", "t"],
                breaks: &["w:p", "w:tr", "w:tbl"],
            },
            Format::Xlsx => XmlSpec {
                text: &["t", "v", "is"],
                breaks: &["si", "row", "c"],
            },
            Format::Pptx => XmlSpec {
                text: &["a:t", "t"],
                breaks: &["a:p", "p"],
            },
        }
    }
}

fn collect_text(xml: &[u8], spec: &XmlSpec<'_>, budget: usize) -> Result<String, ExtractError> {
    let mut reader = Reader::from_reader(xml);
    reader.config_mut().trim_text(false);
    let mut out = String::new();
    let mut stack: Vec<String> = Vec::new();
    let mut buf = Vec::new();
    let mut need_line = false;

    loop {
        match reader.read_event_into(&mut buf) {
            Ok(Event::Start(start)) => {
                let name = String::from_utf8_lossy(start.name().as_ref()).into_owned();
                if spec.breaks.iter().any(|b| *b == name) {
                    need_line = true;
                }
                stack.push(name);
            }
            Ok(Event::Empty(empty)) => {
                let name = String::from_utf8_lossy(empty.name().as_ref()).into_owned();
                if spec.breaks.iter().any(|b| *b == name) {
                    need_line = true;
                }
            }
            Ok(Event::Text(text)) => {
                let top = stack.last().map(String::as_str);
                let collect = match top {
                    Some(current) => spec.text.iter().any(|candidate| candidate == &current),
                    None => false,
                };
                if collect {
                    let piece = text.unescape().map_err(|e| {
                        ExtractError::InvalidArchive(format!("entité XML invalide : {e}"))
                    })?;
                    if !piece.is_empty() {
                        if out.len().saturating_add(piece.len()) > budget {
                            return Err(ExtractError::LimitExceeded(String::from(
                                "le texte extrait dépasse le volume autorisé",
                            )));
                        }
                        out.push_str(piece.as_ref());
                    }
                }
            }
            Ok(Event::End(end)) => {
                let name = String::from_utf8_lossy(end.name().as_ref()).into_owned();
                if spec.breaks.iter().any(|b| *b == name) && need_line {
                    out.push('\n');
                    need_line = false;
                }
                if stack.last().map(String::as_str) == Some(name.as_str()) {
                    stack.pop();
                }
            }
            Ok(Event::Eof) => break,
            Err(e) => {
                return Err(ExtractError::InvalidArchive(format!(
                    "contenu XML illisible : {e}"
                )));
            }
            _ => {}
        }
        buf.clear();
    }
    Ok(out)
}

fn selected_paths(format: Format, entries: &[String]) -> Vec<String> {
    match format {
        Format::Docx => {
            let mut paths = Vec::new();
            if entries.iter().any(|e| e == "word/document.xml") {
                paths.push("word/document.xml".to_string());
            }
            let mut extra: Vec<String> = entries
                .iter()
                .filter(|&e| {
                    (e.starts_with("word/header") || e.starts_with("word/footer"))
                        && e.ends_with(".xml")
                })
                .cloned()
                .collect();
            extra.sort_unstable();
            paths.extend(extra);
            paths
        }
        Format::Xlsx => {
            let mut paths = Vec::new();
            if entries.iter().any(|e| e == "xl/sharedStrings.xml") {
                paths.push("xl/sharedStrings.xml".to_string());
            }
            let mut sheets: Vec<String> = entries
                .iter()
                .filter(|&e| e.starts_with("xl/worksheets/") && e.ends_with(".xml"))
                .cloned()
                .collect();
            sheets.sort_unstable();
            paths.extend(sheets);
            paths
        }
        Format::Pptx => {
            let mut slides: Vec<String> = entries
                .iter()
                .filter(|&e| e.starts_with("ppt/slides/slide") && e.ends_with(".xml"))
                .cloned()
                .collect();
            slides.sort_unstable();
            slides
        }
    }
}

#[cfg(test)]
mod tests {
    use std::io::Write;

    use super::*;

    /// Construit une archive ZIP en mémoire à partir de couples (nom, contenu).
    fn zip_with(files: &[(&str, &[u8])]) -> Vec<u8> {
        let mut writer = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
        let options = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Deflated);
        for (name, content) in files {
            writer.start_file(*name, options).unwrap();
            writer.write_all(content).unwrap();
        }
        writer.finish().unwrap().into_inner()
    }

    const DOCX_BODY: &str = r#"<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
    <w:p><w:r><w:t>Réunion du 14 mars</w:t></w:r></w:p>
    <w:p><w:r><w:t>IBAN fournisseur : FR7600000000000000000000000</w:t></w:r></w:p>
    <w:p><w:r><w:t>Mot de passe : Azerty123&amp;!</w:t></w:r></w:p>
  </w:body>
</w:document>"#;

    #[test]
    fn extrait_du_docx() {
        let bytes = zip_with(&[
            ("word/document.xml", DOCX_BODY.as_bytes()),
            ("[Content_Types].xml", b"<Types/>"),
        ]);
        let extracted = extract(&bytes).unwrap();
        assert_eq!(extracted.format, Format::Docx);
        assert!(extracted.text.contains("Réunion du 14 mars"));
        assert!(extracted.text.contains("FR7600000000000000000000000"));
        // Les entités XML sont déséchappées.
        assert!(extracted.text.contains("Azerty123&!"));
    }

    #[test]
    fn extrait_header_et_footer_docx() {
        let bytes = zip_with(&[
            (
                "word/document.xml",
                b"<w:document xmlns:w=\"w\"><w:body/><w:t></w:t></w:document>".as_slice(),
            ),
            (
                "word/header1.xml",
                b"<w:document xmlns:w=\"w\"><w:t>Confidentiel</w:t></w:document>",
            ),
            (
                "word/footer1.xml",
                b"<w:document xmlns:w=\"w\"><w:t>Page 1</w:t></w:document>",
            ),
        ]);
        let extracted = extract(&bytes).unwrap();
        assert!(extracted.text.contains("Confidentiel"));
        assert!(extracted.text.contains("Page 1"));
    }

    #[test]
    fn extrait_du_xlsx() {
        let shared = r#"<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <si><t>CLÉ PROD</t></si><si><r><t>sk_live_51H8zzzzzzzzzz</t></r></si>
        </sst>"#;
        let sheet = r#"<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <sheetData>
            <row r="1"><c r="A1" t="s"><v>0</v></c></row>
            <row r="2"><c r="A2" t="inlineStr"><is><t>pierre.exemple@exemple.fr</t></is></c></row>
          </sheetData>
        </worksheet>"#;
        let bytes = zip_with(&[
            ("xl/sharedStrings.xml", shared.as_bytes()),
            ("xl/worksheets/sheet1.xml", sheet.as_bytes()),
        ]);
        let extracted = extract(&bytes).unwrap();
        assert_eq!(extracted.format, Format::Xlsx);
        assert!(extracted.text.contains("CLÉ PROD"));
        assert!(extracted.text.contains("pierre.exemple@exemple.fr"));
    }

    #[test]
    fn extrait_du_pptx() {
        let slide = r#"<p:sld xmlns:a="a" xmlns:p="p">
          <p:cSld><p:spTree>
             <p:sp><p:txBody><a:p><a:r><a:t>Jeton de session : eyJhbGciOiJub25lIn0.eyJhbGciOiJub25lIn0</a:t></a:r></a:p></p:txBody></p:sp>
          </p:spTree></p:cSld>
        </p:sld>"#;
        let bytes = zip_with(&[("ppt/slides/slide1.xml", slide.as_bytes())]);
        let extracted = extract(&bytes).unwrap();
        assert_eq!(extracted.format, Format::Pptx);
        assert!(extracted.text.contains("eyJhbGciOiJub25lIn0"));
    }

    #[test]
    fn refuse_une_archive_sans_structure_bureautique() {
        let bytes = zip_with(&[("lisezmoi.txt", b"bonjour")]);
        assert!(matches!(
            extract(&bytes),
            Err(ExtractError::UnknownStructure)
        ));
    }

    #[test]
    fn refuse_du_pas_zip_du_tout() {
        assert!(matches!(
            extract(b"je ne suis pas un zip"),
            Err(ExtractError::NotAnArchive)
        ));
    }

    #[test]
    fn detecte_la_bombe_zip_par_ratio() {
        // 4 Ko compressés en défaut gonflent rarement 512x. On force un ratio
        // invraisemblable avec un champ de 1 Ko qui se décompresse à 1 Mio.
        // Le générateur de test échouerait si la limite n'était pas vérifiée.
        let mut huge = Vec::new();
        while huge.len() < 1_000_000 {
            huge.extend_from_slice(b"AAAAAAAAAAAAAAAA");
        }
        let bytes = zip_with(&[
            ("word/document.xml", huge.as_slice()),
            ("[Content_Types].xml", b"<Types/>"),
        ]);
        assert!(matches!(
            extract(&bytes),
            Err(ExtractError::LimitExceeded(_))
        ));
    }
}
