//! Pondération par contexte lexical (F-13) : un mot-clé à proximité fait
//! monter le niveau, il ne crée jamais une détection à lui seul.

use crate::rule::Context;

fn floor_boundary(s: &str, mut i: usize) -> usize {
    while i > 0 && !s.is_char_boundary(i) {
        i -= 1;
    }
    i
}

fn ceil_boundary(s: &str, i: usize) -> usize {
    let mut i = i.min(s.len());
    while i < s.len() && !s.is_char_boundary(i) {
        i += 1;
    }
    i
}

/// Cherche un mot-clé dans les `window` caractères entourant le candidat,
/// **sans jamais rentrer dans le candidat** : le contexte est ce qui
/// l'entoure, pas ce qui le compose.
pub fn keyword_nearby(text: &str, start: usize, end: usize, ctx: &Context) -> bool {
    if ctx.keywords.is_empty() {
        return false;
    }

    let start = floor_boundary(text, start);
    let end = ceil_boundary(text, end).max(start);
    let win_start = floor_boundary(text, start.saturating_sub(ctx.window));
    let win_end = ceil_boundary(text, end.saturating_add(ctx.window));

    let mut hay = String::new();
    if win_start < start {
        hay.push_str(&text[win_start..start]);
    }
    if end < win_end {
        hay.push_str(&text[end..win_end]);
    }

    if ctx.keywords_case_insensitive {
        let hay = hay.to_lowercase();
        ctx.keywords.iter().any(|k| hay.contains(&k.to_lowercase()))
    } else {
        ctx.keywords.iter().any(|k| hay.contains(k))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ctx(keywords: &[&str]) -> Context {
        Context {
            keywords: keywords.iter().map(|s| s.to_string()).collect(),
            keywords_case_insensitive: true,
            window: 16,
        }
    }

    #[test]
    fn mot_cle_a_gauche_compte() {
        let text = "IBAN : FR7600000000000000000000000";
        let start = text.find("FR76").unwrap();
        let end = start + 4;
        assert!(keyword_nearby(text, start, end, &ctx(&["iban"])));
    }

    #[test]
    fn mot_cle_dans_le_candidat_ne_compte_pas() {
        // Le candidat lui-même contient le mot-clé : ce n'est pas du contexte.
        let text = "iban_value_without_any_keyword";
        let start = 0;
        let end = text.len();
        assert!(!keyword_nearby(text, start, end, &ctx(&["iban"])));
    }

    #[test]
    fn mot_cle_trop_loin_ne_compte_pas() {
        let mut text = "X".repeat(80);
        text.push_str(" IBAN");
        let start = 0;
        let end = 4;
        assert!(!keyword_nearby(&text, start, end, &ctx(&["iban"])));
    }

    #[test]
    fn decoupe_sans_coupure_dans_un_caractere_multibyte() {
        let text = "é".repeat(40);
        let _ = keyword_nearby(&text, 3, 6, &ctx(&["iban"]));
    }
}
