//! Masquage des aperçus et du contenu exporté (F-35).
//!
//! Aucun `Finding` ne transporte l'échantillon brut : tout est reconstruit à
//! partir des offsets absolus, jamais depuis la valeur en clair.

use crate::rule::{Redaction, Strategy};
use crate::Finding;

/// Aperçu pour la fiche : `••••••••••••1234` — seuls les 4 derniers
/// caractères du candidat survivent (F-35).
pub fn preview(redaction: Option<&Redaction>, value: &str) -> String {
    let strategy = redaction.map(|r| r.strategy).unwrap_or(Strategy::KeepLast4);
    match strategy {
        Strategy::TypeOnly => String::from("[masqué]"),
        Strategy::Constant => redaction
            .and_then(|r| r.value.clone())
            .unwrap_or_else(|| String::from("[masqué]")),
        Strategy::KeepLast4 => {
            let chars: Vec<char> = value.chars().collect();
            let keep: String = chars
                .iter()
                .skip(chars.len().saturating_sub(4))
                .copied()
                .collect();
            if chars.len() <= 4 {
                format!("••••{}", keep)
            } else {
                format!("••••••••••••{}", keep)
            }
        }
    }
}

/// Produit le texte de travail avec toutes les détections masquées.
/// Les plages sont traitées de la plus longue à la plus courte, puis par
/// offset croissant : un recouvrement ne fragmente jamais l'échantillon en
/// plusieurs affichages.
pub fn content(text: &str, findings: &[Finding]) -> String {
    if findings.is_empty() {
        return text.to_string();
    }

    let mut ordered: Vec<&Finding> = findings.iter().collect();
    ordered.sort_by(|a, b| {
        (b.end.saturating_sub(b.start))
            .cmp(&(a.end.saturating_sub(a.start)))
            .then(a.start.cmp(&b.start))
            .then(a.id.cmp(&b.id))
    });

    let mut cursor = 0usize;
    let mut out = String::with_capacity(text.len());
    for f in ordered {
        let start = f.start.min(text.len()).max(cursor);
        let end = f.end.min(text.len()).max(start);
        if end <= start {
            continue;
        }
        out.push_str(&text[cursor..start]);
        out.push_str(&format!("[REDACTED:{}]", f.kind));
        cursor = end;
    }
    out.push_str(&text[cursor.min(text.len())..]);
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::rule::{Category, Confidence};
    use crate::Finding;

    fn finding(kind: &str, start: usize, end: usize) -> Finding {
        Finding {
            id: format!("{kind}-{start}"),
            kind: kind.to_string(),
            label_fr: kind.to_string(),
            confidence: Confidence::Probable,
            confidence_reason: String::new(),
            start,
            end,
            line: 1,
            column: 1,
            rule_id: "t".to_string(),
            category: Category::Pii,
            preview_masked: String::new(),
        }
    }

    #[test]
    fn garde_les_quatre_derniers_caracteres() {
        let keep4 = Some(Redaction {
            strategy: Strategy::KeepLast4,
            value: None,
        });
        assert_eq!(
            preview(keep4.as_ref(), "4539148803436467"),
            "••••••••••••6467"
        );
        assert_eq!(preview(keep4.as_ref(), "12345"), "••••••••••••2345");
        assert_eq!(preview(keep4.as_ref(), "123"), "••••123");
        assert_eq!(preview(None, "4539148803436467"), "••••••••••••6467");
    }

    #[test]
    fn strategie_constante_et_masque_plein() {
        let constant = Some(Redaction {
            strategy: Strategy::Constant,
            value: Some(String::from("[en-tête PEM retiré]")),
        });
        assert_eq!(
            preview(constant.as_ref(), "nimporte"),
            "[en-tête PEM retiré]"
        );
        let full = Some(Redaction {
            strategy: Strategy::TypeOnly,
            value: None,
        });
        assert_eq!(preview(full.as_ref(), "nimporte"), "[masqué]");
    }

    #[test]
    fn recouvrement_un_seul_masque() {
        let text = "contact 185013412345649 merci";
        let start = text.find("185013412345649").unwrap();
        let out = content(
            text,
            &[
                finding("nir", start, start + 15),
                finding("card", start, start + 15),
            ],
        );
        assert_eq!(out.matches("[REDACTED:").count(), 1, "{out}");
        assert!(!out.contains("185013412345649"), "{out}");
        assert!(out.contains("contact") && out.contains("merci"), "{out}");
    }

    #[test]
    fn aucun_secret_ne_fuit_dans_le_resultat() {
        let text = "clé: sk_live_abc123XYZ fin";
        let start = text.find("sk_live_abc123XYZ").unwrap();
        let out = content(text, &[finding("secret", start, start + 17)]);
        assert!(!out.contains("abc123XYZ"), "{out}");
        assert!(out.contains("fin"), "{out}");
    }

    #[test]
    fn texte_sans_detection_inchange() {
        assert_eq!(content("rien à voir", &[]), "rien à voir");
    }
}
