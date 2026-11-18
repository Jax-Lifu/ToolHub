use serde::{Deserialize, Serialize};
use std::{
    fs::{self, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TelemetryPoint {
    pub ts_epoch: i64,
    pub soc_temp_c: Option<f64>,
    pub stress_running: bool,
    pub online: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AuditEntry {
    pub ts: String,
    pub actor: String,
    pub role: String,
    pub action: String,
    pub target: String,
    pub detail: String,
    pub ok: bool,
}

fn sanitize_mac_filename(mac: &str) -> String {
    mac.chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '_' })
        .collect()
}

pub fn append_telemetry_point(history_dir: &Path, mac: &str, point: &TelemetryPoint) {
    if fs::create_dir_all(history_dir).is_err() {
        return;
    }
    let file_path = history_dir.join(format!("{}.jsonl", sanitize_mac_filename(mac)));
    if let Ok(line) = serde_json::to_string(point) {
        if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(&file_path) {
            let _ = writeln!(f, "{line}");
        }
    }
}

pub fn read_telemetry_history(history_dir: &Path, mac: &str, limit: usize) -> Vec<TelemetryPoint> {
    let file_path = history_dir.join(format!("{}.jsonl", sanitize_mac_filename(mac)));
    let Ok(raw) = fs::read_to_string(file_path) else {
        return Vec::new();
    };
    let mut points: Vec<TelemetryPoint> = raw
        .lines()
        .filter_map(|line| serde_json::from_str::<TelemetryPoint>(line).ok())
        .collect();
    if points.len() > limit {
        points = points.split_off(points.len() - limit);
    }
    points
}

pub fn append_audit_entry(audit_file: &Path, entry: &AuditEntry) {
    if let Some(parent) = audit_file.parent() {
        let _ = fs::create_dir_all(parent);
    }
    if let Ok(line) = serde_json::to_string(entry) {
        if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(audit_file) {
            let _ = writeln!(f, "{line}");
        }
    }
}

pub fn read_audit_entries(audit_file: &Path, limit: usize) -> Vec<AuditEntry> {
    let Ok(raw) = fs::read_to_string(audit_file) else {
        return Vec::new();
    };
    let mut items: Vec<AuditEntry> = raw
        .lines()
        .filter_map(|line| serde_json::from_str::<AuditEntry>(line).ok())
        .collect();
    items.reverse();
    items.truncate(limit);
    items
}

pub fn ensure_ssh_authorized_key(relay_user: &str, pub_key: &str) -> Result<bool, String> {
    let trimmed = pub_key.trim();
    if trimmed.is_empty() || trimmed.contains('\n') || trimmed.contains('\r') {
        return Err("empty or multiline key".into());
    }
    let parts: Vec<&str> = trimmed.split_whitespace().collect();
    if parts.len() < 2 || parts[0] != "ssh-ed25519" {
        return Err("only ssh-ed25519 keys are accepted".into());
    }
    let key_b64 = parts[1];
    if !key_b64
        .bytes()
        .all(|b| b.is_ascii_alphanumeric() || b == b'+' || b == b'/' || b == b'=')
    {
        return Err("invalid base64 key characters".into());
    }

    let ssh_dir = PathBuf::from(format!("/home/{relay_user}/.ssh"));
    if !ssh_dir.exists() {
        return Ok(false);
    }
    let auth_file = ssh_dir.join("authorized_keys");
    let existing = fs::read_to_string(&auth_file).unwrap_or_default();
    if existing.lines().any(|l| l.contains(key_b64)) {
        return Ok(false);
    }
    let comment = parts
        .get(2)
        .map(|s| {
            s.chars()
                .filter(|c| c.is_ascii_alphanumeric() || matches!(c, '@' | '.' | '-' | '_'))
                .collect::<String>()
        })
        .unwrap_or_else(|| "toolhub-device".to_string());
    let line = format!("ssh-ed25519 {key_b64} {comment}");
    let mut f = OpenOptions::new()
        .create(true)
        .append(true)
        .open(&auth_file)
        .map_err(|e| e.to_string())?;
    writeln!(f, "{line}").map_err(|e| e.to_string())?;
    Ok(true)
}
