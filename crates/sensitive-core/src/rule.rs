//! Structures de règles déclaratives (YAML) et leur validation au chargement.
//!
//! Un contributeur doit pouvoir ajouter une règle sans écrire de Rust : la
//! *forme* est une donnée, la *validation* est de la logique Rust testée
//! unitairement (§5.1 du cahier des charges).

use std::fmt;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

/// Catégories activables sans recompiler (F-20).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Category {
    Credentials,
    Pii,
    Financial,
    Infra,
}

impl Category {
    pub const ALL: [Category; 4] = [
        Category::Credentials,
        Category::Pii,
        Category::Financial,
        Category::Infra,
    ];

    pub fn as_str(self) -> &'static str {
        match self {
            Category::Credentials => "credentials",
            Category::Pii => "pii",
            Category::Financial => "financial",
            Category::Infra => "infra",
        }
    }
}

impl fmt::Display for Category {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

fn default_true() -> bool {
    true
}

fn default_window() -> usize {
    64
}

/// Niveau de confiance à trois paliers (F-12). Jamais un booléen.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Confidence {
    /// Validation structurelle réussie + mot-clé de contexte.
    Certain,
    /// Validation structurelle réussie, ou contexte seul.
    Probable,
    /// Forme compatible uniquement.
    Possible,
}

impl Confidence {
    pub fn as_str(self) -> &'static str {
        match self {
            Confidence::Certain => "certain",
            Confidence::Probable => "probable",
            Confidence::Possible => "possible",
        }
    }

    /// Rang croissant = confiance décroissante : sert aux seuils (`--seuil`).
    /// `Certain` vaut 3, `Probable` 2, `Possible` 1.
    pub fn rank(self) -> u8 {
        match self {
            Confidence::Certain => 3,
            Confidence::Probable => 2,
            Confidence::Possible => 1,
        }
    }

    pub fn parse(s: &str) -> Option<Self> {
        match s.to_ascii_lowercase().as_str() {
            "certain" => Some(Confidence::Certain),
            "probable" => Some(Confidence::Probable),
            "possible" => Some(Confidence::Possible),
            _ => None,
        }
    }
}

impl fmt::Display for Confidence {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Severity {
    Low,
    #[default]
    Medium,
    High,
}

/// Validateurs de §5.2, référencés par nom.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Algorithm {
    /// Aucune validation structurelle : le résultat ne peut pas dépasser ce
    /// que le contexte autorise.
    None,
    Luhn,
    IbanMod97,
    Ean13,
    RibFr,
    FrenchNir,
    Jwt,
    Email,
    AwsAccessKey,
    PrivateKeyPem,
    /// Signal secondaire uniquement (F-16) : jamais seul motif d'un `Certain`.
    Entropy,
}

impl fmt::Display for Algorithm {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let s = match self {
            Algorithm::None => "aucune",
            Algorithm::Luhn => "Luhn",
            Algorithm::IbanMod97 => "ISO 7064 mod 97-10",
            Algorithm::Ean13 => "EAN-13",
            Algorithm::RibFr => "clé RIB mod 97",
            Algorithm::FrenchNir => "clé NIR mod 97",
            Algorithm::Jwt => "structure JWT base64url",
            Algorithm::Email => "syntaxe RFC 5322 simplifiée",
            Algorithm::AwsAccessKey => "préfixe et longueur AWS",
            Algorithm::PrivateKeyPem => "en-tête PEM",
            Algorithm::Entropy => "entropie de Shannon",
        };
        f.write_str(s)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Extract {
    /// Regex du crate `regex` : temps linéaire, aucun retour en arrière.
    pub pattern: String,
    /// Longueur maximale du candidat rejeté au-delà.
    #[serde(default)]
    pub max_length: Option<usize>,
    /// Groupe de capture à retenir (0 = correspondance complète).
    #[serde(default)]
    pub capture: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Validate {
    pub algorithm: Algorithm,
    /// Seuil pour `algorithm: entropy` (bits/symbole).
    #[serde(default)]
    pub min_entropy: Option<f64>,
    /// Exiger le préfixe pays dans l'IBAN.
    #[serde(default)]
    pub require_country_prefix: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Context {
    #[serde(default)]
    pub keywords: Vec<String>,
    #[serde(default = "default_true")]
    pub keywords_case_insensitive: bool,
    /// Distance maximale, en caractères, autour du candidat.
    #[serde(default = "default_window")]
    pub window: usize,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Strategy {
    /// Garder les 4 derniers caractères (F-35).
    #[default]
    KeepLast4,
    /// Masque constant fourni par la règle (utile pour les en-têtes PEM).
    Constant,
    /// Masque plein : aucune information conservée.
    TypeOnly,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Redaction {
    #[serde(default)]
    pub strategy: Strategy,
    #[serde(default)]
    pub value: Option<String>,
}

/// Correspondance nom → niveau, explicite par règle : auditable sans relire
/// le moteur (une règle qui promet un `Certain` sans validation se voit ici).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConfidenceMap {
    pub verified_with_context: Confidence,
    pub verified: Confidence,
    pub format_only: Confidence,
}

impl Default for ConfidenceMap {
    fn default() -> Self {
        ConfidenceMap {
            verified_with_context: Confidence::Certain,
            verified: Confidence::Probable,
            format_only: Confidence::Possible,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Rule {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub severity: Severity,
    /// Identifiant machine du type détecté, exposé dans les rapports JSON.
    #[serde(rename = "type")]
    pub kind: String,
    pub label_fr: String,
    pub category: Category,
    pub extract: Extract,
    #[serde(default)]
    pub validate: Option<Validate>,
    #[serde(default)]
    pub context: Option<Context>,
    #[serde(default)]
    pub redaction: Option<Redaction>,
    #[serde(default)]
    pub confidence: Option<ConfidenceMap>,
    #[serde(default = "default_true")]
    pub enabled: bool,
}

impl Rule {
    pub fn confidence_map(&self) -> ConfidenceMap {
        self.confidence.clone().unwrap_or_default()
    }

    pub fn algorithm(&self) -> Algorithm {
        self.validate
            .as_ref()
            .map(|v| v.algorithm)
            .unwrap_or(Algorithm::None)
    }
}

/// Erreur de chargement avec emplacement, exigé par F-19.
#[derive(Debug)]
pub struct RuleError {
    pub source: Option<PathBuf>,
    pub line: Option<usize>,
    pub column: Option<usize>,
    pub message: String,
    pub rule_id: Option<String>,
}

impl RuleError {
    fn from_serde(err: serde_yaml::Error, source: Option<PathBuf>) -> Self {
        let (line, column) = match err.location() {
            Some(loc) => (Some(loc.index() + 1), Some(loc.column() + 1)),
            None => (None, None),
        };
        RuleError {
            source,
            line,
            column,
            message: err.to_string(),
            rule_id: None,
        }
    }

    fn contextual(mut self, rule_id: &str) -> Self {
        self.rule_id = Some(rule_id.to_string());
        self
    }
}

impl fmt::Display for RuleError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        if let Some(path) = &self.source {
            write!(f, "{}", path.display())?;
            if let (Some(line), Some(column)) = (self.line, self.column) {
                write!(f, ":{line}:{column}")?;
            }
            write!(f, ": ")?;
        }
        write!(f, "{}", self.message)?;
        if let Some(id) = &self.rule_id {
            write!(f, " (règle {id})")?;
        }
        Ok(())
    }
}

impl std::error::Error for RuleError {}

/// Charge une liste de règles depuis un fichier YAML (une séquence).
pub fn load_rules_from_yaml_str(
    src: &str,
    source: Option<PathBuf>,
) -> Result<Vec<Rule>, RuleError> {
    let rules: Vec<Rule> =
        serde_yaml::from_str(src).map_err(|e| RuleError::from_serde(e, source.clone()))?;

    for rule in &rules {
        validate_rule(rule, source.clone())?;
    }
    Ok(rules)
}

/// Charge un fichier unique : soit une séquence de règles, soit une règle.
pub fn load_rule_file(path: &Path) -> Result<Vec<Rule>, RuleError> {
    let src = std::fs::read_to_string(path).map_err(|e| RuleError {
        source: Some(path.to_path_buf()),
        line: None,
        column: None,
        message: format!("impossible de lire le fichier : {e}"),
        rule_id: None,
    })?;
    let source = Some(path.to_path_buf());

    let trimmed = src.trim_start();
    if trimmed.starts_with('-') || trimmed.is_empty() {
        return load_rules_from_yaml_str(&src, source);
    }

    let rule: Rule =
        serde_yaml::from_str(&src).map_err(|e| RuleError::from_serde(e, source.clone()))?;
    let id = rule.id.clone();
    validate_rule(&rule, source).map_err(|e| e.contextual(&id))?;
    Ok(vec![rule])
}

/// Charge tous les fichiers `.yml` / `.yaml` d'un répertoire, triés par nom
/// pour que l'ordre de chargement soit reproductible.
pub fn load_rules_from_dir(dir: &Path) -> Result<Vec<Rule>, RuleError> {
    let mut paths: Vec<PathBuf> = std::fs::read_dir(dir)
        .map_err(|e| RuleError {
            source: Some(dir.to_path_buf()),
            line: None,
            column: None,
            message: format!("impossible de lire le répertoire : {e}"),
            rule_id: None,
        })?
        .filter_map(|entry| entry.ok().map(|e| e.path()))
        .filter(|p| {
            p.extension()
                .and_then(|e| e.to_str())
                .map(|e| e.eq_ignore_ascii_case("yml") || e.eq_ignore_ascii_case("yaml"))
                .unwrap_or(false)
        })
        .collect();
    paths.sort();

    let mut rules = Vec::new();
    for path in paths {
        rules.extend(load_rule_file(&path)?);
    }
    Ok(rules)
}

/// Validation du schéma d'une règle (F-18) : échoue au chargement, jamais en
/// cours d'analyse.
pub fn validate_rule(rule: &Rule, source: Option<PathBuf>) -> Result<(), RuleError> {
    let fail = |message: String| RuleError {
        source: source.clone(),
        line: None,
        column: None,
        message,
        rule_id: Some(rule.id.clone()),
    };

    if rule.id.trim().is_empty() {
        return Err(fail("identifiant de règle vide".into()));
    }
    if rule.kind.trim().is_empty() {
        return Err(fail(format!(
            "règle « {} » : champ « type » manquant",
            rule.id
        )));
    }
    if rule.label_fr.trim().is_empty() {
        return Err(fail(format!(
            "règle « {} » : champ « label_fr » manquant",
            rule.id
        )));
    }

    if rule.extract.pattern.is_empty() {
        return Err(fail(format!(
            "règle « {} » : motif d'extraction vide",
            rule.id
        )));
    }
    if let Err(e) = regex::Regex::new(&rule.extract.pattern) {
        return Err(fail(format!(
            "règle « {} » : motif invalide : {}",
            rule.id, e
        )));
    }
    if let Some(max) = rule.extract.max_length {
        if max == 0 {
            return Err(fail(format!(
                "règle « {} » : max_length doit être strictement positif",
                rule.id
            )));
        }
    }

    if let Some(validate) = &rule.validate {
        if validate.algorithm == Algorithm::Entropy && validate.min_entropy.is_none() {
            return Err(fail(format!(
                "règle « {} » : l'algorithme « entropy » exige « min_entropy »",
                rule.id
            )));
        }
        if validate.min_entropy.is_some() && validate.algorithm != Algorithm::Entropy {
            return Err(fail(format!(
                "règle « {} » : « min_entropy » n'est valable que pour l'algorithme « entropy »",
                rule.id
            )));
        }
        if let Some(min) = validate.min_entropy {
            if !(0.0..=8.0).contains(&min) {
                return Err(fail(format!(
                    "règle « {} » : min_entropy doit être dans [0, 8], reçu {min}",
                    rule.id
                )));
            }
        }
    }

    if let Some(redaction) = &rule.redaction {
        if redaction.strategy == Strategy::Constant && redaction.value.is_none() {
            return Err(fail(format!(
                "règle « {} » : la stratégie « constant » exige « value »",
                rule.id
            )));
        }
    }

    if let Some(context) = &rule.context {
        if context.keywords.is_empty() {
            return Err(fail(format!(
                "règle « {} » : liste de mots-clés vide",
                rule.id
            )));
        }
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn motif_invalide_signale_avec_la_regle() {
        let yaml = r#"
- id: test.bad
  name: Règle cassée
  type: X
  label_fr: X
  category: pii
  extract:
    pattern: "(unclosed"
"#;
        let err = load_rules_from_yaml_str(yaml, None).unwrap_err();
        assert!(err.message.contains("motif invalide"), "{}", err.message);
        assert_eq!(err.rule_id.as_deref(), Some("test.bad"));
    }

    #[test]
    fn entropy_sans_seuil_rejetee() {
        let yaml = r#"
- id: test.entropy
  name: t
  type: X
  label_fr: X
  category: pii
  extract:
    pattern: "abc"
  validate:
    algorithm: entropy
"#;
        let err = load_rules_from_yaml_str(yaml, None).unwrap_err();
        assert!(err.message.contains("min_entropy"), "{}", err.message);
    }

    #[test]
    fn regle_valide_se_charge() {
        let yaml = r#"
- id: fin.iban
  name: IBAN
  severity: high
  type: IBAN
  label_fr: Compte bancaire (IBAN)
  category: financial
  extract:
    pattern: '[A-Z]{2}\d{2}[A-Z0-9]{10,30}'
    max_length: 34
  validate:
    algorithm: iban_mod97
  context:
    keywords: [iban, rib]
  redaction:
    strategy: keep_last4
"#;
        let rules = load_rules_from_yaml_str(yaml, None).unwrap();
        assert_eq!(rules.len(), 1);
        assert_eq!(rules[0].category, Category::Financial);
        assert_eq!(rules[0].algorithm(), Algorithm::IbanMod97);
    }
}
