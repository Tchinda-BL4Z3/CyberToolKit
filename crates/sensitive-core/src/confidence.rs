//! Passage d'un résultat de validation à un niveau de confiance (F-12).
//!
//! La correspondance est **déclarée par la règle** : on peut auditer une règle
//! qui promet un `Certain` sans relire le moteur.

use crate::rule::{Algorithm, Confidence, ConfidenceMap};
use crate::validators::{Detail, Outcome};

/// État d'un candidat après extraction, validation et lecture du contexte.
#[derive(Debug, Clone)]
pub struct Evaluation {
    pub algorithm: Algorithm,
    pub outcome: Outcome,
    pub has_context: bool,
    pub context_window: usize,
    pub detail: Option<Detail>,
}

impl Evaluation {
    pub fn score(&self, map: &ConfidenceMap) -> (Confidence, String) {
        let detail = self
            .detail
            .as_ref()
            .map(|d| format!(" ({})", d.text))
            .unwrap_or_default();

        match self.outcome {
            Outcome::Verified if self.has_context => (
                map.verified_with_context,
                format!(
                    "Validation {} réussie, mot-clé de contexte à moins de {} caractères{}.",
                    self.algorithm, self.context_window, detail
                ),
            ),
            Outcome::Verified => (
                map.verified,
                format!("Validation {} réussie, sans mot-clé de contexte{}.", self.algorithm, detail),
            ),
            Outcome::Rejected => (
                map.format_only,
                format!(
                    "Format compatible, mais validation {} échouée{}.",
                    self.algorithm, detail
                ),
            ),
            Outcome::NotApplicable if self.has_context => (
                map.verified_with_context,
                format!(
                    "Aucune validation structurelle ; mot-clé de contexte à moins de {} caractères{}.",
                    self.context_window, detail
                ),
            ),
            Outcome::NotApplicable => (
                map.format_only,
                format!(
                    "Format compatible uniquement : ni validation structurelle ni mot-clé de contexte{}.",
                    detail
                ),
            ),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::rule::{Algorithm, Confidence};

    fn map() -> ConfidenceMap {
        ConfidenceMap::default()
    }

    #[test]
    fn valide_avec_contexte_donnereniveau_max() {
        let ev = Evaluation {
            algorithm: Algorithm::IbanMod97,
            outcome: Outcome::Verified,
            has_context: true,
            context_window: 64,
            detail: None,
        };
        let (level, reason) = ev.score(&map());
        assert_eq!(level, Confidence::Certain);
        assert!(reason.contains("7064"), "{reason}");
    }

    #[test]
    fn valide_sans_contexte_nest_pas_certain() {
        let ev = Evaluation {
            algorithm: Algorithm::Luhn,
            outcome: Outcome::Verified,
            has_context: false,
            context_window: 64,
            detail: None,
        };
        let (level, _) = ev.score(&map());
        assert_eq!(level, Confidence::Probable);
    }

    #[test]
    fn validation_echouee_retombe_en_possible() {
        let ev = Evaluation {
            algorithm: Algorithm::IbanMod97,
            outcome: Outcome::Rejected,
            has_context: true,
            context_window: 64,
            detail: None,
        };
        let (level, reason) = ev.score(&map());
        assert_eq!(level, Confidence::Possible);
        assert!(reason.contains("chou"), "{reason}");
    }
}
