//! `sensitive-core` — le moteur de détection de ProjectSecurityScanner.
//!
//! Propriétés du cahier des charges :
//! * **Aucune dépendance réseau** (F-67) — vérifiée par test automatisé.
//! * Extraction linéaire via `regex` (F-19), `fancy-regex` interdit (§4.3).
//! * Trois niveaux de confiance (F-12) attribués par la règle, auditable.
//! * Un `Finding` ne transporte jamais l'échantillon brut (F-35) : seul un
//!   aperçu masqué et des offsets absolus circulent.

pub mod confidence;
pub mod context;
pub mod engine;
pub mod redact;
pub mod rule;
pub mod validators;

use std::collections::BTreeMap;
use std::fmt;
use std::path::PathBuf;

pub use engine::{CompiledRule, LineIndex, ScanOptions, ScanReport};
pub use rule::{Algorithm, Category, Confidence, Context, Redaction, Rule, RuleError, Severity};

/// Catalogue embarqué dans le binaire : aucune règle à télécharger, aucun
/// réseau requis pour démarrer.
pub const EMBEDDED_SOURCES: [(&str, &str); 4] = [
    ("credentials.yaml", include_str!("rules/credentials.yaml")),
    ("financial.yaml", include_str!("rules/financial.yaml")),
    ("pii.yaml", include_str!("rules/pii.yaml")),
    ("infra.yaml", include_str!("rules/infra.yaml")),
];

/// Aperçu masqué, offsets absolus, ligne/colonne — jamais la valeur en clair.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Finding {
    pub id: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub label_fr: String,
    pub confidence: Confidence,
    pub confidence_reason: String,
    pub start: usize,
    pub end: usize,
    pub line: usize,
    pub column: usize,
    pub rule_id: String,
    pub category: Category,
    pub preview_masked: String,
}

/// Catalogue de règles compilé : l'analyse est impossible sans lui.
pub struct RuleSet {
    rules: Vec<CompiledRule>,
    by_id: BTreeMap<String, usize>,
}

impl RuleSet {
    pub fn from_rules(rules: Vec<Rule>) -> Result<RuleSet, RuleError> {
        let mut seen = BTreeMap::new();
        let mut compiled = Vec::with_capacity(rules.len());

        for rule in rules {
            if rule.id.trim().is_empty() {
                return Err(RuleError {
                    source: None,
                    line: None,
                    column: None,
                    message: String::from("identifiant de règle vide"),
                    rule_id: None,
                });
            }
            if seen.contains_key(&rule.id) {
                return Err(RuleError {
                    source: None,
                    line: None,
                    column: None,
                    message: format!("identifiant de règle dupliqué : {}", rule.id),
                    rule_id: Some(rule.id.clone()),
                });
            }
            rule::validate_rule(&rule, None)?;
            seen.insert(rule.id.clone(), ());
            compiled.push(CompiledRule::compile(rule)?);
        }

        compiled.sort_by(|a, b| a.rule.id.cmp(&b.rule.id));
        let by_id = compiled
            .iter()
            .enumerate()
            .map(|(i, c)| (c.rule.id.clone(), i))
            .collect();
        Ok(RuleSet {
            rules: compiled,
            by_id,
        })
    }

    /// Catalogue embarqué dans le binaire : aucune règle à télécharger.
    pub fn embedded() -> RuleSet {
        let mut rules = Vec::new();
        for (source, text) in EMBEDDED_SOURCES {
            match rule::load_rules_from_yaml_str(text, Some(PathBuf::from(source))) {
                Ok(mut r) => rules.append(&mut r),
                Err(e) => panic!("règle embarquée invalide ({source}) : {e}"),
            }
        }
        RuleSet::from_rules(rules).expect("catalogue embarqué invalide")
    }

    /// Chargement depuis un dossier de fichiers `.yaml` (ajout de règles).
    pub fn from_dir(path: &std::path::Path) -> Result<RuleSet, RuleError> {
        RuleSet::from_rules(rule::load_rules_from_dir(path)?)
    }

    pub fn iter(&self) -> impl Iterator<Item = &CompiledRule> {
        self.rules.iter()
    }

    pub fn get(&self, id: &str) -> Option<&Rule> {
        self.by_id.get(id).map(|&i| &self.rules[i].rule)
    }

    pub fn len(&self) -> usize {
        self.rules.len()
    }

    pub fn is_empty(&self) -> bool {
        self.rules.is_empty()
    }

    /// Liste compacte pour `scanner rules list`.
    pub fn describe(&self) -> Vec<(String, String, Category, Confidence, bool)> {
        self.rules
            .iter()
            .map(|c| {
                (
                    c.rule.id.clone(),
                    c.rule.label_fr.clone(),
                    c.rule.category,
                    c.rule.confidence_map().verified_with_context,
                    c.rule.enabled,
                )
            })
            .collect()
    }
}

impl fmt::Debug for RuleSet {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "RuleSet({} règles)", self.rules.len())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn catalogue_embarque_complet() {
        let set = RuleSet::embedded();
        assert!(set.len() >= 10, "seulement {} règles", set.len());
        for (id, _label, _cat, _conf, _enabled) in set.describe() {
            assert!(set.get(&id).is_some());
        }
    }

    #[test]
    fn identifiant_duplique_rejete() {
        let yaml = r#"
- id: demo.1
  name: Essai
  type: demo
  label_fr: Essai
  category: pii
  extract:
    pattern: "abc"
"#;
        let rules = rule::load_rules_from_yaml_str(yaml, None).unwrap();
        let err = RuleSet::from_rules(vec![rules[0].clone(), rules[0].clone()]).unwrap_err();
        assert!(err.to_string().contains("dupliqué"), "{err}");
    }

    #[test]
    fn une_finding_ne_porte_jamais_le_secret_en_clair() {
        let set = RuleSet::embedded();
        let text = "carte 4539148803436467 IBAN FR7600000000000000000000000";
        let report = engine::scan(text, &set, &ScanOptions::all());
        assert!(!report.findings.is_empty());
        let json = serde_json::to_string(&report.findings).unwrap();
        assert!(
            !json.contains("4539148803436467"),
            "échantillon brut : {json}"
        );
        assert!(
            !json.contains("FR7600000000000000000000000"),
            "IBAN brut : {json}"
        );
        assert!(
            json.contains("6467"),
            "4 derniers chiffres attendus : {json}"
        );
    }
}
