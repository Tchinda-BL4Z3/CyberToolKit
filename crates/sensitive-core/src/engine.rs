//! Pipeline d'analyse : extraction linéaire (`regex`), validation, contexte,
//! pondération, offsets absolus, dédoublonnage.

use std::collections::HashMap;
use std::path::PathBuf;

use regex::Regex;

use crate::confidence::Evaluation;
use crate::context::keyword_nearby;
use crate::rule::{Algorithm, Category, Context, Rule, RuleError};
use crate::validators::run;
use crate::{Finding, RuleSet};

fn default_error(message: String, rule_id: Option<String>) -> RuleError {
    RuleError {
        source: None,
        line: None,
        column: None,
        message,
        rule_id,
    }
}

/// Options de scan. Par défaut : toutes les catégories, aucun seuil.
#[derive(Debug, Clone)]
pub struct ScanOptions {
    /// Catégories activées (F-62). Vide = toutes.
    pub categories: Vec<Category>,
    /// Niveau minimum conservé (`--seuil probable`, F-63).
    pub min_level: Option<crate::Confidence>,
    /// Longueur maximum d'un candidat, en caractères (garde-fou F-20).
    pub max_candidate_chars: usize,
}

impl Default for ScanOptions {
    fn default() -> Self {
        ScanOptions {
            categories: Vec::new(),
            min_level: None,
            max_candidate_chars: 4096,
        }
    }
}

impl ScanOptions {
    pub fn all() -> Self {
        ScanOptions::default()
    }

    fn category_enabled(&self, c: Category) -> bool {
        self.categories.is_empty() || self.categories.contains(&c)
    }
}

/// Règle compilée : impossible d'analyser sans `Regex` validée à la construction.
pub struct CompiledRule {
    pub rule: Rule,
    pub regex: Regex,
}

impl CompiledRule {
    pub fn compile(rule: Rule) -> Result<CompiledRule, RuleError> {
        let id = rule.id.clone();
        let regex = Regex::new(&rule.extract.pattern).map_err(|e| {
            default_error(
                format!("expression régulière invalide : {e}"),
                Some(id.clone()),
            )
        })?;
        let captures = regex.captures_len();
        if rule.extract.capture >= captures {
            return Err(default_error(
                format!(
                    "le groupe de capture {} n'existe pas (le motif n'en a que {})",
                    rule.extract.capture,
                    captures.saturating_sub(1)
                ),
                Some(id),
            ));
        }
        Ok(CompiledRule { rule, regex })
    }
}

/// Spécificité du validateur : à niveau égal, on retient la règle dont la
/// validation rejette le plus d'entrées (EAN-13 n'accepte que 13 chiffres,
/// le Luhn tolère de 13 à 19).
fn specificity(a: Algorithm) -> u8 {
    match a {
        Algorithm::FrenchNir | Algorithm::IbanMod97 | Algorithm::RibFr => 6,
        Algorithm::Ean13 => 5,
        Algorithm::Luhn => 4,
        Algorithm::AwsAccessKey | Algorithm::PrivateKeyPem | Algorithm::Jwt | Algorithm::Email => 3,
        Algorithm::Entropy => 2,
        Algorithm::None => 1,
    }
}

/// Index des débuts de ligne pour convertir un offset en (ligne, colonne).
pub struct LineIndex {
    starts: Vec<usize>,
}

impl LineIndex {
    pub fn new(text: &str) -> LineIndex {
        let mut starts = vec![0usize];
        for (i, b) in text.bytes().enumerate() {
            if b == b'\n' {
                starts.push(i + 1);
            }
        }
        LineIndex { starts }
    }

    /// Ligne (1-based) et colonne (en caractères) d'un offset donné.
    pub fn locate(&self, text: &str, offset: usize) -> (usize, usize) {
        let offset = offset.min(text.len());
        let line_idx = match self.starts.binary_search(&offset) {
            Ok(i) => i,
            Err(i) => i.saturating_sub(1),
        };
        let start = self.starts[line_idx];
        let column = text[start..offset].chars().count() + 1;
        (line_idx + 1, column)
    }
}

#[derive(Debug)]
pub struct ScanReport {
    pub findings: Vec<Finding>,
    pub rules_evaluated: usize,
    pub candidates_seen: usize,
    pub candidates_rejected: usize,
}

/// Analyse le texte donné. Déterministe : même entrée, même rapport.
pub fn scan(text: &str, rules: &RuleSet, opts: &ScanOptions) -> ScanReport {
    let index = LineIndex::new(text);
    let mut findings: Vec<Finding> = Vec::new();
    let mut rules_evaluated = 0usize;
    let mut candidates_seen = 0usize;
    let mut candidates_rejected = 0usize;

    let no_context = Context {
        keywords: Vec::new(),
        keywords_case_insensitive: true,
        window: 64,
    };

    for compiled in rules.iter() {
        let rule = &compiled.rule;
        if !rule.enabled {
            continue;
        }
        if !opts.category_enabled(rule.category) {
            continue;
        }
        rules_evaluated += 1;

        let context = rule.context.as_ref().unwrap_or(&no_context);
        let map = rule.confidence_map();
        let algorithm = rule.algorithm();

        for caps in compiled.regex.captures_iter(text) {
            let whole = match caps.get(0) {
                Some(m) => m,
                None => continue,
            };
            let capture = caps.get(rule.extract.capture).unwrap_or(whole);
            candidates_seen += 1;

            let candidate = capture.as_str();
            let char_count = candidate.chars().count();
            if opts.max_candidate_chars > 0 && char_count > opts.max_candidate_chars {
                candidates_rejected += 1;
                continue;
            }
            if let Some(max) = rule.extract.max_length {
                if char_count > max {
                    candidates_rejected += 1;
                    continue;
                }
            }

            let (outcome, detail) = run(algorithm, candidate, rule.validate.as_ref());
            let has_context = keyword_nearby(text, capture.start(), capture.end(), context);
            let evaluation = Evaluation {
                algorithm,
                outcome,
                has_context,
                context_window: context.window,
                detail,
            };
            let (confidence, reason) = evaluation.score(&map);

            if let Some(min) = opts.min_level {
                if confidence.rank() < min.rank() {
                    continue;
                }
            }

            let (line, column) = index.locate(text, capture.start());
            findings.push(Finding {
                id: format!("{}:{}:{}", rule.id, capture.start(), capture.end()),
                kind: rule.kind.clone(),
                label_fr: rule.label_fr.clone(),
                confidence,
                confidence_reason: reason,
                start: capture.start(),
                end: capture.end(),
                line,
                column,
                rule_id: rule.id.clone(),
                category: rule.category,
                preview_masked: crate::redact::preview(rule.redaction.as_ref(), candidate),
            });
        }
    }

    findings.sort_by(|a, b| {
        a.start
            .cmp(&b.start)
            .then(b.end.cmp(&a.end))
            .then(a.id.cmp(&b.id))
    });

    let specs: HashMap<&str, u8> = rules
        .iter()
        .map(|c| (c.rule.id.as_str(), specificity(c.rule.algorithm())))
        .collect();
    let deduped = dedupe(findings, &specs);

    ScanReport {
        findings: deduped,
        rules_evaluated,
        candidates_seen,
        candidates_rejected,
    }
}

/// Même plage de caractères, plusieurs règles : on garde la plus fiable,
/// puis le validateur le plus restrictif, puis l'identifiant (ordre stable).
fn dedupe(findings: Vec<Finding>, specs: &HashMap<&str, u8>) -> Vec<Finding> {
    let mut kept: Vec<Finding> = Vec::with_capacity(findings.len());
    for f in findings {
        match kept
            .iter()
            .position(|k| k.start == f.start && k.end == f.end)
        {
            None => kept.push(f),
            Some(i) => {
                if wins_over(&f, &kept[i], specs) {
                    kept[i] = f;
                }
            }
        }
    }
    kept
}

fn wins_over(candidate: &Finding, current: &Finding, specs: &HashMap<&str, u8>) -> bool {
    use crate::Confidence;
    let rank = |c: Confidence| match c {
        Confidence::Certain => 3,
        Confidence::Probable => 2,
        Confidence::Possible => 1,
    };
    let (cr, k) = (rank(candidate.confidence), rank(current.confidence));
    if cr != k {
        return cr > k;
    }
    let cs = specs.get(candidate.rule_id.as_str()).copied().unwrap_or(0);
    let ks = specs.get(current.rule_id.as_str()).copied().unwrap_or(0);
    if cs != ks {
        return cs > ks;
    }
    candidate.rule_id > current.rule_id
}

/// Rapport JSON partagé par la CLI, le WASM et le navigateur.
pub fn to_json_report(file: &str, encoding: &str, report: &ScanReport) -> String {
    let mut value = report_json(report);
    if let Some(object) = value.as_object_mut() {
        object.insert(
            String::from("file"),
            serde_json::Value::String(file.to_string()),
        );
        object.insert(
            String::from("encoding"),
            serde_json::Value::String(encoding.to_string()),
        );
    }
    serde_json::to_string_pretty(&value).unwrap_or_else(|_| String::from("{}"))
}

/// Rapport sans contexte de fichier : utilisé par la cible WebAssembly.
pub fn report_to_json(report: &ScanReport) -> String {
    serde_json::to_string(&report_json(report)).unwrap_or_else(|_| String::from("{}"))
}

fn report_json(report: &ScanReport) -> serde_json::Value {
    let mut counts = [0usize; 3];
    for f in &report.findings {
        let i = match f.confidence {
            crate::Confidence::Certain => 0,
            crate::Confidence::Probable => 1,
            crate::Confidence::Possible => 2,
        };
        counts[i] += 1;
    }
    let findings: Vec<serde_json::Value> = report
        .findings
        .iter()
        .map(serde_json::to_value)
        .collect::<Result<_, _>>()
        .unwrap_or_default();
    serde_json::json!({
        "rulesEvaluated": report.rules_evaluated,
        "candidatesSeen": report.candidates_seen,
        "candidatesRejected": report.candidates_rejected,
        "counts": {
            "certain": counts[0],
            "probable": counts[1],
            "possible": counts[2],
            "total": report.findings.len(),
        },
        "findings": findings,
    })
}

/// Aide pour les tests et la CLI : source fictive des erreurs de chargement.
pub fn rules_error_source(name: &str) -> PathBuf {
    PathBuf::from(name)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Confidence;

    #[test]
    fn line_index_localise_exactement() {
        let text = "abc\ndéf\n\nghi";
        let idx = LineIndex::new(text);
        assert_eq!(idx.locate(text, 0), (1, 1));
        assert_eq!(idx.locate(text, 4), (2, 1));
        assert_eq!(idx.locate(text, 7), (2, 3)); // « é » = 2 octets
        assert_eq!(idx.locate(text, 8), (2, 4));
        assert_eq!(idx.locate(text, 9), (3, 1));
        assert_eq!(idx.locate(text, 10), (4, 1));
    }

    #[test]
    fn scan_deterministe_et_tri() {
        let rules = RuleSet::embedded();
        let text = "IBAN FR7600000000000000000000000 puis carte 4539148803436467";
        let a = scan(text, &rules, &ScanOptions::all());
        let b = scan(text, &rules, &ScanOptions::all());
        assert_eq!(a.findings.len(), b.findings.len());
        assert!(!a.findings.is_empty(), "aucune détection : {a:?}");
        let mut prev = 0usize;
        for f in &a.findings {
            assert!(f.start >= prev);
            prev = f.start;
        }
        assert!(
            a.findings.iter().any(|f| f.rule_id == "fin.iban"),
            "{:?}",
            a.findings
        );
        assert!(
            a.findings.iter().any(|f| f.rule_id == "fin.card"),
            "{:?}",
            a.findings
        );
    }

    #[test]
    fn seuil_min_filtre_les_possible() {
        let rules = RuleSet::embedded();
        let text = "IBAN FR7600000000000000000000000";
        let mut opts = ScanOptions::all();
        opts.min_level = Some(Confidence::Probable);
        let high = scan(text, &rules, &opts);
        assert!(
            high.findings
                .iter()
                .all(|f| f.confidence != Confidence::Possible),
            "{:?}",
            high.findings
        );
    }

    #[test]
    fn categorie_desactivee_exclut_ses_regles() {
        let rules = RuleSet::embedded();
        let text = "IBAN FR7600000000000000000000000";
        let mut opts = ScanOptions::all();
        opts.categories = vec![Category::Credentials];
        let r = scan(text, &rules, &opts);
        assert!(r.findings.is_empty(), "{:?}", r.findings);
    }

    #[test]
    fn le_fichier_etoile_ne_produit_jamais_de_faux_positif() {
        let rules = RuleSet::embedded();
        let text = "Le 14 mars, 12 h 30, 1000 unités, 42 réponses, v1.2.3, 2026-10-07";
        let r = scan(text, &rules, &ScanOptions::all());
        assert!(
            r.findings
                .iter()
                .all(|f| f.confidence == Confidence::Possible || f.category == Category::Pii),
            "{:?}",
            r.findings
        );
    }

    #[test]
    fn rapport_json_contient_les_compteurs() {
        let rules = RuleSet::embedded();
        let report = scan("carte 4539148803436467", &rules, &ScanOptions::all());
        let json = to_json_report("memo.txt", "utf-8", &report);
        assert!(json.contains("\"total\""), "{json}");
        assert!(json.contains("memo.txt"), "{json}");
        assert!(
            !json.contains("4539148803436467"),
            "secret en clair : {json}"
        );
    }
}
