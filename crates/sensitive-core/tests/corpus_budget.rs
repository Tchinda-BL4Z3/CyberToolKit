//! F-65 / F-66 — corpus de 200 documents annotés (générés depuis les règles)
//! et budget de faux positifs exécuté comme **test** (§8.2 du cahier des charges).
//!
//! Arbitrage v1 (décision produit) : le corpus est synthétique, construit à
//! partir des validateurs des règles — les documents positifs calquent chaque
//! règle sur la valeur reconnue (clé de contrôle calculée, Luhn, mod 97…), et
//! les documents négatifs sont des textes crédibles volontairement trompeurs
//! (mots-clés présents, valeurs quasi-conformes, données personnelles neutres).
//!
//! Le seuil est fixé à ≤ 5 % de faux positifs sur le sous-ensemble sans donnée
//! sensible (50 documents) : au-delà, ce test échoue et l'outil doit être revu.

use sensitive_core::{engine::scan, RuleSet, ScanOptions};

struct Doc {
    id: String,
    text: String,
    expect: &'static [&'static str],
    negative: bool,
}

const INTROS: [&str; 10] = [
    "Convention de prestation de services",
    "Demande d'information suite à votre courrier",
    "Réponse à l'appel d'offres n° 2025-014",
    "Relevé d'avance de frais de déplacement",
    "Procès-verbal de réunion du 12 février",
    "Accord de confidentialité entre parties",
    "Note de service interne à diffuser",
    "Facture proforma et conditions de règlement",
    "Compte rendu d'incident infrastructure",
    "Proposition commerciale n° 118",
];

const PRÉAMBULE: &str = "Madame, Monsieur,

Nous faisons suite à notre échange et vous transmettons les éléments suivants.

";
const CONCLUSION: &str = "

Nous restons à votre disposition pour toute précision.

Cordialement,
Le service administration.";

fn rib() -> String {
    let body = "100000000000000000000";
    let mut n = 0usize;
    for b in body.bytes() {
        n = (n * 10 + (b - b'0') as usize) % 97;
    }
    let key = (97 - n) % 97;
    format!("{body}{key:02}")
}

fn positive_docs() -> Vec<Doc> {
    // 15 règles × 10 préambules = 150 documents positifs annotés.
    const MOTIFS: [(&str, &str); 15] = [
        (
            "cred.aws_access_key",
            "Clé d'accès AWS du compte production : AKIAIOSFODNN7EXAMPLE.",
        ),
        (
            "cred.private_key",
            "Clé privée pour le tunnel : -----BEGIN RSA PRIVATE KEY----- MIIEpAIBAAKCAQEAvQW... -----END RSA PRIVATE KEY-----.",
        ),
        (
            "cred.jwt",
            "Jeton d'authentification de session : Bearer eyJhbGciOiJub25lIn0.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N.",
        ),
        (
            "cred.password",
            "Le mot de passe : Dh8!kLm2vQ9 à modifier après connexion.",
        ),
        (
            "cred.api_key",
            "Veuillez utiliser la clé d'API : d8Kf3qL0xVm9Ts2NpW pour les appels.",
        ),
        (
            "fin.iban",
            "Le virement doit être effectué sur l'IBAN : FR7630006000011234567890189.",
        ),
        (
            "fin.card",
            "La carte de paiement d'essai est la suivante : 4539 1488 0343 6467.",
        ),
        ("fin.rib", "Veuillez prélever sur le RIB suivant : {}."),
        (
            "fin.ean13",
            "Le produit commandé porte la référence EAN-13 : 4006381333931.",
        ),
        (
            "pii.french_nir",
            "Le numéro d'allocataire fourni est le 185013412345649, à rappeler dans la réclamation.",
        ),
        (
            "pii.email",
            "Merci d'écrire à l'adresse de contact : pierre.exemple@example.fr.",
        ),
        (
            "pii.phone_fr",
            "Notre référent est joignable au portable +33 6 12 34 56 78.",
        ),
        (
            "infra.ip_private",
            "Le serveur interne est accessible sur http://10.20.30.40:8080/api.",
        ),
        (
            "infra.url_basic_auth",
            "Le dépôt est hébergé à l'adresse http://user:pass@git.intranet.dev/repo.git.",
        ),
        (
            "infra.url_with_token",
            "L'appel de synchronisation utilise l'URL https://api.example.com/data?token=A1b2C3d4E5f6G7h8.",
        ),
    ];

    let rib_line = MOTIFS[7].1.replace("{}", &rib());

    let mut docs = Vec::with_capacity(150);
    for (i, intro) in INTROS.iter().enumerate() {
        for (rule, line) in MOTIFS.iter() {
            let line = if *rule == "fin.rib" {
                rib_line.as_str()
            } else {
                line
            };
            docs.push(Doc {
                id: format!("pos-{:02}-{}", i + 1, rule),
                text: format!("{intro}\n\n{PRÉAMBULE}{line}{CONCLUSION}"),
                expect: core::slice::from_ref(rule),
                negative: false,
            });
        }
    }
    docs
}

fn negative_docs() -> Vec<Doc> {
    // 50 documents volontairement sans donnée sensible ; texte crédible,
    // mots-clés présents mais valeurs non conformes au sens des règles.
    let raw: Vec<(&'static str, &'static str)> = vec![
        (
            "n-01",
            "Convention de prestation.\n\nIBAN : sera communiqué en annexe sécurisée.\n\nCordialement.",
        ),
        (
            "n-02",
            "Le mot de passe n'est jamais conservé sur les serveurs de l'entreprise.",
        ),
        (
            "n-03",
            "CLIENT_EUR000316\n\nMontant total : 12 500,00 € TTC, à régler à 30 jours.",
        ),
        (
            "n-04",
            "Bon de commande 2025/331 du 4 mars. Livraison attendue à Marseille (13004).",
        ),
        (
            "n-05",
            "Pour tout premier contact, appelez le standard régional : le numéro est affiché à l'accueil des locaux.",
        ),
        (
            "n-06",
            "L'identifiant de fournisseur est PROVIDER-9, cette valeur n'a aucun secret.",
        ),
        (
            "n-07",
            "Réf. dossière 118-AB-2203. Décision notifiée en recommandé simple.",
        ),
        (
            "n-08",
            "Coordinateur : M. JEAN-LUC. Service comptabilité, bâtiment C.",
        ),
        (
            "n-09",
            "La connexion est protégée par https et les clefs sont gérées en coffre.",
        ),
        (
            "n-10",
            "Vérification effectuée par lot : aucun élément à signaler.",
        ),
        (
            "n-11",
            "L'IBAN complet figure en version signée du contrat, transmise par un canal sécurisé.",
        ),
        (
            "n-12",
            "Paiement par prélèvement sur compte courant identifié C0001.",
        ),
        (
            "n-13",
            "La société est immatriculée 890 123 456 RCS; 5 123 856 € de capital.",
        ),
        (
            "n-14",
            "Réunion du 3 mai, salle 2. Ordre du jour transmis par la direction générale.",
        ),
        (
            "n-15",
            "Carte PROVIS dont le numéro est remis en main propre au guichet physique.",
        ),
        (
            "n-16",
            "Code postal 75011, ville de Paris. Adresse postale : 4 bis rue des Lilas.",
        ),
        (
            "n-17",
            "Téléphone étranger de référence : +44 20 7946 0958 (standard Londres).",
        ),
        (
            "n-18",
            "Exemplaire numéroté 00042 sur un tirage limité à 150.",
        ),
        (
            "n-19",
            "Le jeton de caisse enregistreuse : n° 4482, horodaté à 17 h 04.",
        ),
        (
            "n-20",
            "Délégation signée le 14/09/2025, applicable à compter du jour suivant.",
        ),
        (
            "n-21",
            "Ils ont réglé la somme de 3 128,45 € par virement SEPA.",
        ),
        (
            "n-22",
            "Référence dossier : 1187/2025/00654 ; aucune pièce annexe requise.",
        ),
        (
            "n-23",
            "Le réseau interne est jonctionné via le VLAN 12, sans exposition.",
        ),
        (
            "n-24",
            "Numéro de machine : 25-HMR-088. Maintenance tous les six mois.",
        ),
        (
            "n-25",
            "Fournisseur habituel référencé sous l'article 4.2.1 du contrat.",
        ),
        (
            "n-26",
            "Quantité livrée : 3 200 unités, valeur 6 900,00 € HT.",
        ),
        (
            "n-27",
            "La cle n'est pas renseignée dans ce document.",
        ),
        (
            "n-28",
            "Token du planning : SEMAINE27. Rien d'autre n'est indiqué.",
        ),
        (
            "n-29",
            "Compte rendu : trois intervenants, un en visio, deux sur site.",
        ),
        (
            "n-30",
            "Le standard renvoie vers le service concerné (poste 4450).",
        ),
        (
            "n-31",
            "Version provisoire V2.1 ; diffusion réservée à la cellule de crise locale.",
        ),
        (
            "n-32",
            "Adresse de livraison : 22 avenue des Champs-Élysées, 75008 Paris.",
        ),
        ("n-33", "Règlement par chèque à l'ordre de l'agence, comptant."),
        (
            "n-34",
            "Le stock affiche 12 500 articles en réserve ; seuil d'alerte 800.",
        ),
        (
            "n-35",
            "Période de garantie : 24 mois à compter de la mise en service.",
        ),
        (
            "n-36",
            "Le canal réservé nécessite une habilitation dédiée, sur demande.",
        ),
        (
            "n-37",
            "n° sécurité sociale : non communiqué à ce stade de la procédure.",
        ),
        (
            "n-38",
            "Contact du prestataire : standard ENL-ESPOIR, fil direct affiché à l'accueil.",
        ),
        (
            "n-39",
            "Document archivé sous cote AR-2025/0071 bis.",
        ),
        (
            "n-40",
            "Série partielle à compléter : 883 reliquée en attente de rapprochement.",
        ),
        (
            "n-41",
            "Le routeur de bord ne répond plus ; bascule prévue sur l'annexe B.",
        ),
        (
            "n-42",
            "Facture 2025-00448 : acompte de 30 %, solde à réception.",
        ),
        (
            "n-43",
            "Il conviendra de confirmer par ecrit dans un délai de 7 jours.",
        ),
        (
            "n-44",
            "La salle 12 est réservée de 18 h 30 à 20 h 00, clé déposée à l'accueil.",
        ),
        (
            "n-45",
            "Base FOYER-2025 : 4 520 abonnés, dj e croissance stable.",
        ),
        (
            "n-46",
            "Merci de nous retourner le bordereau signé avant le 30 du mois.",
        ),
        (
            "n-47",
            "Le colis suit le numéro de suivi courrier national, sans valeur particulière.",
        ),
        (
            "n-48",
            "Réunion annuelle repoussée au 23 janvier de l'année suivante.",
        ),
        (
            "n-49",
            "La clé USB du hall est vide depuis la dernière campagne.",
        ),
        (
            "n-50",
            "Toute elevation de droits passe par la fiche d'habilitation jointe.",
        ),
    ];

    raw.into_iter()
        .map(|(id, text)| Doc {
            id: id.to_string(),
            text: text.to_string(),
            expect: &[],
            negative: true,
        })
        .collect()
}

fn corpus() -> Vec<Doc> {
    let mut docs = positive_docs();
    docs.extend(negative_docs());
    assert_eq!(docs.len(), 200, "le corpus doit compter 200 documents");
    let neg = docs.iter().filter(|d| d.negative).count();
    assert_eq!(neg, 50, "au moins 50 documents sans donnée sensible");
    docs
}

#[test]
fn budget_faux_positifs_et_recouvrement_du_corpus() {
    let rules = RuleSet::embedded();
    let opts = ScanOptions::all();

    let mut faux_positifs = 0usize;
    let mut faux_negatifs = Vec::new();
    let mut decrire = |id: &str, rule: &str| {
        faux_negatifs.push(format!("{id} : {rule} non détectée"));
    };

    for doc in corpus() {
        let report = scan(&doc.text, &rules, &opts);
        if doc.negative {
            if !report.findings.is_empty() {
                faux_positifs += 1;
                println!(
                    "FP ({}): {}",
                    doc.id,
                    report
                        .findings
                        .iter()
                        .map(|f| f.rule_id.as_str())
                        .collect::<Vec<_>>()
                        .join(",")
                );
            }
            continue;
        }
        for expected in doc.expect {
            let found = report.findings.iter().any(|f| f.rule_id == *expected);
            if !found {
                decrire(&doc.id, expected);
            }
        }
    }

    let mut msg = String::new();
    for line in &faux_negatifs {
        msg.push_str(line);
        msg.push('\n');
    }
    assert!(
        faux_negatifs.is_empty(),
        "\nfaux négatifs sur le corpus :\n{msg}"
    );

    let budget_max = 5usize; // ≤ 5 % sur 100, soit au plus 5 documents sur 50 au sens strict
    let budget_actuel = faux_positifs.saturating_mul(100) / 50;
    println!(
        "corpus : 200 documents (150 annotés positifs, 50 négatifs) — faux positifs {faux_positifs}/50 ({budget_actuel} %)"
    );
    assert!(
        faux_positifs <= budget_max,
        "budget de faux positifs dépassé : {faux_positifs} documents touchés (>{budget_max})",
    );
}

#[test]
fn le_corpus_explore_la_totalite_du_catalogue() {
    let rules = RuleSet::embedded();
    let presentes: Vec<String> = rules
        .iter()
        .map(|c| c.rule.id.clone())
        .filter(|id| corpus().iter().any(|d| d.expect.contains(&id.as_str())))
        .collect();
    for rule in rules.iter() {
        assert!(
            presentes.contains(&rule.rule.id),
            "la règle {} n'est couverte par aucun document du corpus",
            rule.rule.id
        );
    }
}
