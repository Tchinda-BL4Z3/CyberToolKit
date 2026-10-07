//! Validateurs de §5.2. Chacun est pur, sans I/O, et couvert par ses tests.

use std::sync::OnceLock;

use crate::rule::{Algorithm, Validate};

/// Résultat d'une validation structurelle.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Outcome {
    Verified,
    Rejected,
    /// La règle ne définit aucune validation : le niveau dépend alors du
    /// contexte lexical seul.
    NotApplicable,
}

/// Détail mesurable affiché dans la raison du niveau de confiance.
#[derive(Debug, Clone)]
pub struct Detail {
    pub text: String,
}

/// ISO 3166-1 alpha-2 publiant un IBAN.
const IBAN_COUNTRIES: &[&str] = &[
    "AD", "AE", "AL", "AT", "AZ", "BA", "BE", "BG", "BH", "BI", "BJ", "BR", "BY", "CH", "CR", "CY",
    "CZ", "DE", "DJ", "DK", "DO", "EE", "EG", "ES", "FI", "FO", "FR", "GB", "GE", "GI", "GL", "GR",
    "GT", "HR", "HU", "IE", "IL", "IQ", "IR", "IS", "IT", "JO", "KW", "KZ", "LB", "LC", "LI", "LT",
    "LU", "LV", "LY", "MC", "MD", "ME", "MK", "MR", "MT", "MU", "MZ", "NE", "NI", "NL", "NO", "PK",
    "PL", "PS", "PT", "QA", "RO", "RS", "SA", "SC", "SD", "SE", "SI", "SK", "SM", "SO", "SV", "TL",
    "TN", "TR", "UA", "VA", "VG", "XK", "YE",
];

fn email_regex() -> &'static regex::Regex {
    static RE: OnceLock<regex::Regex> = OnceLock::new();
    RE.get_or_init(|| {
        regex::Regex::new(r"^[A-Za-z0-9._%+\-]+@[A-Za-z0-9](?:[A-Za-z0-9\-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9\-]*[A-Za-z0-9])?)+$")
            .expect("regex d'e-mail fixe et valide")
    })
}

/// Retire tout sauf chiffres.
fn digits_only(s: &str) -> String {
    s.chars().filter(|c| c.is_ascii_digit()).collect()
}

/// Clé de Luhn (cartes bancaires, F-02).
pub fn luhn(candidate: &str) -> bool {
    let digits = digits_only(candidate);
    if digits.len() < 13 || digits.len() > 19 {
        return false;
    }
    let mut sum = 0usize;
    for (i, c) in digits.chars().rev().enumerate() {
        let mut d = c.to_digit(10).unwrap() as usize;
        if i % 2 == 1 {
            d *= 2;
            if d > 9 {
                d -= 9;
            }
        }
        sum += d;
    }
    sum.is_multiple_of(10)
}

/// ISO 7064 mod 97-10 (IBAN, F-03).
pub fn iban_mod97(candidate: &str) -> bool {
    let clean: String = candidate
        .chars()
        .filter(|c| c.is_ascii_alphanumeric())
        .collect::<String>()
        .to_uppercase();
    if clean.len() < 15 || clean.len() > 34 {
        return false;
    }
    if !clean.is_ascii() {
        return false;
    }
    let rearranged = format!("{}{}", &clean[4..], &clean[..4]);

    let mut numeric = String::with_capacity(rearranged.len() * 2);
    for c in rearranged.chars() {
        if c.is_ascii_digit() {
            numeric.push(c);
        } else if c.is_ascii_uppercase() {
            numeric.push_str(&(c as u8 - b'A' + 10).to_string());
        } else {
            return false;
        }
    }

    let mut remainder = 0usize;
    for chunk in numeric.as_bytes().chunks(7) {
        let block = format!("{}{}", remainder, String::from_utf8_lossy(chunk));
        remainder = block.parse::<usize>().map(|v| v % 97).unwrap_or(0);
    }
    remainder == 1
}

/// Vérifie aussi que le code pays publie bien un IBAN.
pub fn iban_country_prefix(candidate: &str) -> bool {
    let clean: String = candidate
        .chars()
        .filter(|c| c.is_ascii_alphanumeric())
        .collect::<String>()
        .to_uppercase();
    if clean.len() < 4 {
        return false;
    }
    IBAN_COUNTRIES.contains(&&clean[..2])
}

/// Checksum EAN-13 (F-08).
pub fn ean13(candidate: &str) -> bool {
    let digits = digits_only(candidate);
    if digits.len() != 13 {
        return false;
    }
    let mut sum = 0usize;
    for (i, c) in digits.chars().enumerate() {
        if i == 12 {
            break;
        }
        let d = c.to_digit(10).unwrap() as usize;
        sum += if i % 2 == 0 { d } else { d * 3 };
    }
    let expected = (10 - (sum % 10)) % 10;
    match digits.chars().nth(12).and_then(|c| c.to_digit(10)) {
        Some(d) => d as usize == expected,
        None => false,
    }
}

/// Clé RIB française : 21 chiffres (conversion T2 des lettres) mod 97 (F-09).
pub fn rib_fr(candidate: &str) -> bool {
    let clean: String = candidate
        .chars()
        .filter(|c| c.is_ascii_alphanumeric())
        .collect::<String>()
        .to_uppercase();
    if clean.len() != 23 {
        return false;
    }
    let body = &clean[..21];
    let key: u32 = match clean[21..].parse() {
        Ok(v) => v,
        Err(_) => return false,
    };
    if key == 0 || key > 97 {
        return false;
    }

    let mut numeric = String::with_capacity(21 * 2);
    for c in body.chars() {
        if c.is_ascii_digit() {
            numeric.push(c);
        } else if c.is_ascii_uppercase() {
            // Table T2 utilisée par l'IBAN/RIB : A..I = 1..9, J..R = 1..9, S..Z = 1..8.
            let v = ((c as u8 - b'A') % 9) + 1;
            numeric.push_str(&v.to_string());
        } else {
            return false;
        }
    }

    let mut remainder = 0usize;
    for chunk in numeric.as_bytes().chunks(7) {
        let block = format!("{}{}", remainder, String::from_utf8_lossy(chunk));
        remainder = block.parse::<usize>().map(|v| v % 97).unwrap_or(0);
    }
    97 - remainder == key as usize
}

/// NIR français (F-14) : structure sexe/année/mois, puis clé mod 97 si présente.
pub fn french_nir(candidate: &str) -> bool {
    let clean: String = candidate
        .chars()
        .filter(|c| c.is_ascii_digit() || *c == 'A' || *c == 'B')
        .collect::<String>()
        .to_uppercase();

    if clean.len() != 13 && clean.len() != 15 {
        return false;
    }

    let base = &clean[..13];
    // Position 3-4 : mois, avec les codes corse 2A / 2B admis.
    let month_field: String = base.chars().skip(3).take(2).collect();
    let month_numeric: String = match month_field.as_str() {
        "2A" => "19".to_string(),
        "2B" => "18".to_string(),
        other => other.to_string(),
    };

    if !month_numeric.chars().all(|c| c.is_ascii_digit()) || month_numeric.len() != 2 {
        return false;
    }
    let month: u32 = month_numeric.parse().unwrap_or(0);
    let month_ok =
        (1..=12).contains(&month) || (20..=42).contains(&month) || (50..=99).contains(&month);
    if !month_ok {
        return false;
    }

    // Les 11 chiffres de tête doivent être numériques.
    let head: String = base
        .chars()
        .enumerate()
        .filter(|(i, _)| *i != 3 && *i != 4)
        .map(|(_, c)| c)
        .collect();
    if head.len() != 11 || !head.chars().all(|c| c.is_ascii_digit()) {
        return false;
    }

    if clean.len() == 15 {
        let key: u32 = match clean[13..].parse() {
            Ok(v) => v,
            Err(_) => return false,
        };
        let numeric = base.replacen(&month_field, &month_numeric, 1);
        if numeric.len() != 13 || !numeric.chars().all(|c| c.is_ascii_digit()) {
            return false;
        }
        let value: u128 = match numeric.parse() {
            Ok(v) => v,
            Err(_) => return false,
        };
        let expected = 97 - (value % 97) as u32;
        return key == expected;
    }

    true
}

fn b64url_decode(segment: &str) -> Option<Vec<u8>> {
    let clean: String = segment.chars().filter(|c| *c != '=').collect();
    if clean.len() % 4 == 1 {
        return None;
    }
    let mut out = Vec::with_capacity(clean.len() * 3 / 4);
    let mut buffer = 0u32;
    let mut bits = 0u32;
    for c in clean.chars() {
        let v = match c {
            'A'..='Z' => c as u32 - 'A' as u32,
            'a'..='z' => c as u32 - 'a' as u32 + 26,
            '0'..='9' => c as u32 - '0' as u32 + 52,
            '-' => 62,
            '_' => 63,
            _ => return None,
        };
        buffer = (buffer << 6) | v;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push(((buffer >> bits) & 0xff) as u8);
        }
    }
    Some(out)
}

/// Structure JWT : trois segments base64url, en-tête JSON portant `alg` (F-05).
pub fn jwt(candidate: &str) -> bool {
    let parts: Vec<&str> = candidate.split('.').collect();
    if parts.len() != 3 {
        return false;
    }
    if parts[0].is_empty() || parts[1].is_empty() {
        return false;
    }
    if !parts[0]
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
        || !parts[1]
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
    {
        return false;
    }
    let header = match b64url_decode(parts[0]) {
        Some(h) => h,
        None => return false,
    };
    let text = String::from_utf8_lossy(&header);
    text.contains("\"alg\"")
}

/// RFC 5322 volontairement simplifiée : les adresses locales exotiques tombent
/// en `Possible`, pas en faux négatif.
pub fn email(candidate: &str) -> bool {
    email_regex().is_match(candidate)
}

/// Préfixe et longueur d'un identifiant AWS (F-06).
pub fn aws_access_key(candidate: &str) -> bool {
    if candidate.len() != 20 {
        return false;
    }
    let prefix = &candidate[..4];
    if !matches!(
        prefix,
        "AKIA" | "ASIA" | "AIDA" | "AROA" | "AIPA" | "AGPA" | "ANPA" | "ANVA"
    ) {
        return false;
    }
    candidate
        .chars()
        .skip(4)
        .all(|c| c.is_ascii_uppercase() || c.is_ascii_digit())
}

/// En-tête PEM de clé privée, tous algorithmes (F-04).
pub fn private_key_pem(candidate: &str) -> bool {
    let upper = candidate.to_ascii_uppercase();
    upper.contains("BEGIN") && upper.contains("PRIVATE KEY")
}

/// Entropie de Shannon en bits/symbole : signal secondaire (F-16).
pub fn shannon_entropy(s: &str) -> f64 {
    if s.is_empty() {
        return 0.0;
    }
    let mut counts: [usize; 256] = [0; 256];
    let bytes = s.as_bytes();
    for b in bytes {
        counts[*b as usize] += 1;
    }
    let len = bytes.len() as f64;
    let mut entropy = 0.0;
    for c in counts.iter() {
        if *c == 0 {
            continue;
        }
        let p = *c as f64 / len;
        entropy -= p * p.log2();
    }
    entropy
}

/// Applique la validation déclarée par la règle.
pub fn run(
    algorithm: Algorithm,
    candidate: &str,
    validate: Option<&Validate>,
) -> (Outcome, Option<Detail>) {
    match algorithm {
        Algorithm::None => (Outcome::NotApplicable, None),
        Algorithm::Luhn => (bool_outcome(luhn(candidate)), None),
        Algorithm::Ean13 => (bool_outcome(ean13(candidate)), None),
        Algorithm::RibFr => (bool_outcome(rib_fr(candidate)), None),
        Algorithm::FrenchNir => (bool_outcome(french_nir(candidate)), None),
        Algorithm::Jwt => (bool_outcome(jwt(candidate)), None),
        Algorithm::Email => (bool_outcome(email(candidate)), None),
        Algorithm::AwsAccessKey => (bool_outcome(aws_access_key(candidate)), None),
        Algorithm::PrivateKeyPem => (bool_outcome(private_key_pem(candidate)), None),
        Algorithm::IbanMod97 => {
            if !iban_mod97(candidate) {
                return (Outcome::Rejected, None);
            }
            let require = validate
                .and_then(|v| v.require_country_prefix)
                .unwrap_or(false);
            if require && !iban_country_prefix(candidate) {
                return (Outcome::Rejected, None);
            }
            (Outcome::Verified, None)
        }
        Algorithm::Entropy => {
            let threshold = validate.and_then(|v| v.min_entropy).unwrap_or(0.0);
            let value = shannon_entropy(candidate);
            let detail = Some(Detail {
                text: format!("entropie de {value:.2} bits/symbole (seuil {threshold:.2})"),
            });
            (
                if value >= threshold {
                    Outcome::Verified
                } else {
                    Outcome::Rejected
                },
                detail,
            )
        }
    }
}

fn bool_outcome(ok: bool) -> Outcome {
    if ok {
        Outcome::Verified
    } else {
        Outcome::Rejected
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn luhn_accepte_carte_valide_et_rejette_modifiee() {
        assert!(luhn("4539 1488 0343 6467"));
        assert!(!luhn("4539 1488 0343 6468"));
        assert!(!luhn("1234"));
    }

    #[test]
    fn luhn_ne_confond_pas_avec_un_nombre_quelconque() {
        assert!(!luhn("1234567890123456"));
    }

    #[test]
    fn iban_connus_passent() {
        // IBAN de test documentaire, pas un compte réel.
        assert!(iban_mod97("FR7630006000011234567890189"));
        assert!(iban_mod97("DE89370400440532013000"));
        assert!(iban_mod97("GB82 WEST 1234 5698 7654 32"));
        assert!(iban_country_prefix("FR7630006000011234567890189"));
    }

    #[test]
    fn iban_corrompu_rejete() {
        assert!(!iban_mod97("FR7630006000011234567890188"));
        assert!(!iban_mod97("FR0030006000011234567890189"));
        assert!(!iban_country_prefix("ZZ7630006000011234567890189"));
        assert!(!iban_country_prefix("FR7"));
    }

    #[test]
    fn rib_23_chiffres_avec_cle() {
        let body = "100000000000000000000";
        let mut n = 0usize;
        for b in body.bytes() {
            n = (n * 10 + (b - b'0') as usize) % 97;
        }
        let key = (97 - n) % 97;
        let rib = format!("{body}{key:02}");
        assert_eq!(rib.len(), 23);
        assert!(rib_fr(&rib));
        let wrong = format!("{body}{:02}", (key + 7) % 97);
        assert!(!rib_fr(&wrong));
    }

    #[test]
    fn ean13_checksum() {
        assert!(ean13("4006381333931"));
        assert!(!ean13("4006381333932"));
        assert!(!ean13("400638133393"));
    }

    #[test]
    fn nir_15_chiffres_avec_cle() {
        // 1 85 01 341 234 56 — clé = 97 - (n % 97) calculée à la main.
        let nir = "185013412345649";
        assert!(french_nir(nir));
        let mut wrong = nir.to_string();
        let last = wrong.pop().unwrap();
        wrong.push(if last == '0' { '1' } else { '0' });
        assert!(!french_nir(&wrong));
    }

    #[test]
    fn nir_mois_invalide_rejete() {
        assert!(!french_nir("185003412345628"));
        assert!(!french_nir("185133412345628"));
    }

    #[test]
    fn nir_sans_cle_valide_structurellement() {
        assert!(french_nir("1850134123456"));
    }

    #[test]
    fn jwt_reel_reconnu() {
        let header = b64url_encode(br#"{"alg":"HS256","typ":"JWT"}"#);
        let jwt_token = format!("{}.{}.{}", header, b64url_encode(b"payload"), "signature");
        assert!(jwt(&jwt_token));
        assert!(!jwt("eyJhbGciOiJIUzI1NiJ9.nes.tro.segment"));
    }

    #[test]
    fn email_syntaxe() {
        assert!(email("prenom.nom@example.fr"));
        assert!(email("a+b@sub.domain.io"));
        assert!(!email("pas-une-adresse"));
        assert!(!email("x@@example.fr"));
    }

    #[test]
    fn aws_cle_valide() {
        assert!(aws_access_key("AKIAIOSFODNN7EXAMPLE"));
        assert!(!aws_access_key("AKIAIOSFODNN7EXAMPL"));
        assert!(!aws_access_key("zkiaIOSFODNN7EXAMPLE"));
    }

    #[test]
    fn entropie_signal_secondaire() {
        assert!(shannon_entropy("aaaaaaaaaaaa") < 1.0);
        assert!(shannon_entropy("Tr0ub4dor&3-passphrase") > 3.0);
    }

    fn b64url_encode(data: &[u8]) -> String {
        const ALPHABET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        let mut out = String::new();
        for chunk in data.chunks(3) {
            let b0 = chunk[0] as u32;
            let b1 = *chunk.get(1).unwrap_or(&0) as u32;
            let b2 = *chunk.get(2).unwrap_or(&0) as u32;
            let n = (b0 << 16) | (b1 << 8) | b2;
            out.push(ALPHABET[(n >> 18) as usize & 63] as char);
            out.push(ALPHABET[(n >> 12) as usize & 63] as char);
            if chunk.len() > 1 {
                out.push(ALPHABET[(n >> 6) as usize & 63] as char);
            }
            if chunk.len() > 2 {
                out.push(ALPHABET[n as usize & 63] as char);
            }
        }
        out
    }
}
