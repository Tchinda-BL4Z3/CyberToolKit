//! `scanner` — CLI de ProjectSecurityScanner (F-44).
//!
//! Première livraison du cahier des charges : analyser un document en local,
//! sortir un rapport JSON, et ressortir un code d'exploitation exploitable
//! en intégration continue.

mod decode;

use std::io::Read;
use std::path::PathBuf;
use std::process::ExitCode;

use clap::{Args, Parser, Subcommand};
use zeroize::{Zeroize, Zeroizing};

use sensitive_core::engine::{self, ScanOptions};
use sensitive_core::{Category, Confidence, RuleSet};

#[derive(Parser)]
#[command(
    name = "scanner",
    version,
    about = "ProjectSecurityScanner — détection locale de données sensibles",
    long_about = "Analyse un document sur la machine : aucun réseau, aucune télémétrie, aucun échantillon transmis."
)]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Analyse un ou plusieurs fichiers
    Scan(ScanArgs),
    /// Interroge le catalogue de règles embarqué
    Rules {
        #[command(subcommand)]
        command: RulesCommand,
    },
}

#[derive(Args)]
struct ScanArgs {
    /// Fichiers à analyser
    #[arg(value_name = "FICHIER")]
    files: Vec<PathBuf>,

    /// Lit le document sur l'entrée standard
    #[arg(long)]
    stdin: bool,

    /// Format de sortie
    #[arg(long, default_value = "json")]
    format: Format,

    /// Ajoute le texte complet avec les détections masquées
    #[arg(long)]
    redact: bool,

    /// Seuil minimal de confiance conservé (certain | probable | possible)
    #[arg(long)]
    seuil: Option<String>,

    /// Catégories à activer, séparées par des virgules
    #[arg(long, value_delimiter = ',')]
    categories: Vec<String>,

    /// Code de sortie 2 dès qu'une détection est trouvée
    #[arg(long)]
    exit_code: bool,
}

#[derive(clap::ValueEnum, Clone, Copy)]
enum Format {
    Json,
    Text,
}

#[derive(Subcommand)]
enum RulesCommand {
    /// Liste les règles chargées
    List {
        /// Affiche aussi les motifs
        #[arg(long)]
        verbose: bool,
    },
    /// Détaille une règle donnée
    Explain { id: String },
}

fn main() -> ExitCode {
    let cli = Cli::parse();
    match cli.command {
        Command::Scan(args) => run_scan(args),
        Command::Rules { command } => run_rules(command),
    }
}

fn run_scan(args: ScanArgs) -> ExitCode {
    let rules = RuleSet::embedded();

    let mut options = ScanOptions::all();
    options.categories = match parse_categories(&args.categories) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("erreur : {e}");
            return ExitCode::from(1);
        }
    };
    if let Some(seuil) = &args.seuil {
        match Confidence::parse(seuil) {
            Some(level) => options.min_level = Some(level),
            None => {
                eprintln!(
                    "erreur : seuil inconnu « {seuil} » (attendu : certain, probable, possible)"
                );
                return ExitCode::from(1);
            }
        }
    }

    let inputs = match collect_inputs(&args) {
        Ok(i) => i,
        Err(e) => {
            eprintln!("erreur : {e}");
            return ExitCode::from(1);
        }
    };

    let mut reports = Vec::new();
    let mut findings_total = 0usize;
    for mut input in inputs {
        let report = engine::scan(&input.text, &rules, &options);
        findings_total += report.findings.len();
        let mut json = engine::to_json_report(&input.name, &input.encoding, &report);
        if args.redact {
            json = inject_redacted(
                &json,
                &sensitive_core::redact::content(&input.text, &report.findings),
            );
        }
        reports.push(match args.format {
            Format::Json => json,
            Format::Text => text_report(&input.name, &input.encoding, &report),
        });
        input.text.zeroize();
    }

    match args.format {
        Format::Json if reports.len() == 1 => println!("{}", reports[0]),
        Format::Json => println!("[{}]", reports.join(",\n")),
        Format::Text => println!("{}", reports.join("\n")),
    }

    if args.exit_code && findings_total > 0 {
        ExitCode::from(2)
    } else {
        ExitCode::SUCCESS
    }
}

struct Input {
    name: String,
    encoding: String,
    text: String,
}

fn collect_inputs(args: &ScanArgs) -> Result<Vec<Input>, String> {
    let mut inputs = Vec::new();
    if args.stdin {
        let mut bytes = Zeroizing::new(Vec::new());
        std::io::stdin()
            .read_to_end(&mut bytes)
            .map_err(|e| format!("impossible de lire l'entrée standard : {e}"))?;
        let decoded = decode::decode(&bytes)?;
        inputs.push(Input {
            name: String::from("<stdin>"),
            encoding: decoded.encoding.to_string(),
            text: decoded.text,
        });
    }
    for path in &args.files {
        let bytes =
            Zeroizing::new(std::fs::read(path).map_err(|e| format!("{} : {e}", path.display()))?);
        let decoded = decode::decode_named(&bytes, &path.display().to_string())?;
        inputs.push(Input {
            name: path.display().to_string(),
            encoding: decoded.encoding.to_string(),
            text: decoded.text,
        });
    }
    if inputs.is_empty() {
        return Err(String::from(
            "aucun document à analyser : indiquez un fichier ou --stdin",
        ));
    }
    Ok(inputs)
}

fn parse_categories(values: &[String]) -> Result<Vec<Category>, String> {
    let mut out = Vec::new();
    for value in values {
        let found = Category::ALL
            .into_iter()
            .find(|c| c.as_str() == value.as_str());
        match found {
            Some(c) => out.push(c),
            None => {
                return Err(format!(
                    "catégorie inconnue « {value} » (attendu : credentials, pii, financial, infra)"
                ))
            }
        }
    }
    Ok(out)
}

/// Injecte le texte masqué dans le rapport JSON sans casser sa structure.
fn inject_redacted(json: &str, redacted: &str) -> String {
    let mut value: serde_json::Value = match serde_json::from_str(json) {
        Ok(v) => v,
        Err(_) => return json.to_string(),
    };
    if let Some(object) = value.as_object_mut() {
        object.insert(
            String::from("redactedContent"),
            serde_json::Value::String(redacted.to_string()),
        );
    }
    serde_json::to_string_pretty(&value).unwrap_or_else(|_| json.to_string())
}

fn text_report(file: &str, encoding: &str, report: &engine::ScanReport) -> String {
    let mut out = format!(
        "{file} [{encoding}] — {} détection(s)\n",
        report.findings.len()
    );
    if report.findings.is_empty() {
        out.push_str("  rien à signaler sur le texte lu\n");
        return out;
    }
    for f in &report.findings {
        out.push_str(&format!(
            "  {}:{} [{}] {} — {} ({})\n",
            f.line, f.column, f.confidence, f.label_fr, f.preview_masked, f.rule_id
        ));
    }
    out
}

fn run_rules(command: RulesCommand) -> ExitCode {
    let rules = RuleSet::embedded();
    match command {
        RulesCommand::List { verbose } => {
            println!("IDENTIFIANT              CATEGORIE    PLAFOND    LIBELLÉ");
            for (id, label, category, ceiling, enabled) in rules.describe() {
                let state = if enabled { "" } else { " (désactivée)" };
                println!("{id:<24} {category:<12} {ceiling:<10} {label}{state}");
                if verbose {
                    if let Some(rule) = rules.get(&id) {
                        println!("    motif : {}", rule.extract.pattern);
                        println!("    validation : {}", rule.algorithm());
                    }
                }
            }
            println!("\n{} règle(s) chargée(s)", rules.len());
            ExitCode::SUCCESS
        }
        RulesCommand::Explain { id } => match rules.get(&id) {
            None => {
                eprintln!("erreur : règle « {id} » introuvable");
                ExitCode::from(1)
            }
            Some(rule) => {
                println!("identifiant   : {}", rule.id);
                println!("libellé      : {}", rule.label_fr);
                println!("catégorie    : {}", rule.category);
                println!("sévérité     : {:?}", rule.severity);
                println!("type         : {}", rule.kind);
                println!("motif        : {}", rule.extract.pattern);
                println!("validation   : {}", rule.algorithm());
                println!("activée      : {}", rule.enabled);
                let map = rule.confidence_map();
                println!(
                    "confiance    : contexte → {}, vérifié → {}, forme → {}",
                    map.verified_with_context, map.verified, map.format_only
                );
                if let Some(ctx) = &rule.context {
                    println!(
                        "contexte     : {} caractères autour, mots-clés : {}",
                        ctx.window,
                        ctx.keywords.join(", ")
                    );
                } else {
                    println!("contexte     : aucun mot-clé");
                }
                ExitCode::SUCCESS
            }
        },
    }
}
