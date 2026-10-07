//! Budgets de sécurité appliqués à la lecture des archives (F-31).

/// Limites partagées par toutes les entrées d'extraction.
#[derive(Debug, Clone, Copy)]
pub struct ZipLimits {
    /// Nombre maximum d'entrées dans l'archive.
    pub max_entries: usize,
    /// Volume décompressé total autorisé.
    pub max_uncompressed_total: usize,
    /// Ratio compressé → décompressé au-delà duquel l'entrée est suspecte.
    pub max_compression_ratio: usize,
}
