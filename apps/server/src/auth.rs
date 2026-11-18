use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use hmac::{Hmac, Mac};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{collections::HashMap, fs, path::Path};
use uuid::Uuid;

type HmacSha256 = Hmac<Sha256>;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Role {
    Admin,
    Operator,
    Viewer,
}

impl Role {
    pub fn as_str(&self) -> &'static str {
        match self {
            Role::Admin => "admin",
            Role::Operator => "operator",
            Role::Viewer => "viewer",
        }
    }

    pub fn can_operate(&self) -> bool {
        matches!(self, Role::Admin | Role::Operator)
    }

    pub fn is_admin(&self) -> bool {
        matches!(self, Role::Admin)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UserRecord {
    pub username: String,
    pub password_hash: String,
    pub salt: String,
    pub role: Role,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UserView {
    pub username: String,
    pub role: Role,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TokenClaims {
    pub sub: String,
    pub role: Role,
    pub exp: i64,
}

pub fn load_or_create_secret(path: &Path) -> Vec<u8> {
    if let Ok(raw) = fs::read_to_string(path) {
        let trimmed = raw.trim();
        if !trimmed.is_empty() {
            return trimmed.as_bytes().to_vec();
        }
    }
    let secret = format!("{}{}", Uuid::new_v4().simple(), Uuid::new_v4().simple());
    let _ = fs::write(path, &secret);
    secret.into_bytes()
}

pub fn hash_password(salt: &str, password: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(salt.as_bytes());
    hasher.update(b":");
    hasher.update(password.as_bytes());
    hex::encode(hasher.finalize())
}

pub fn make_user(username: &str, password: &str, role: Role) -> UserRecord {
    let salt = Uuid::new_v4().simple().to_string();
    UserRecord {
        username: username.to_string(),
        password_hash: hash_password(&salt, password),
        salt,
        role,
        created_at: chrono::Utc::now().to_rfc3339(),
    }
}

pub fn verify_password(user: &UserRecord, password: &str) -> bool {
    let candidate = hash_password(&user.salt, password);
    if candidate.len() != user.password_hash.len() {
        return false;
    }
    let mut diff = 0u8;
    for (a, b) in candidate.bytes().zip(user.password_hash.bytes()) {
        diff |= a ^ b;
    }
    diff == 0
}

pub fn load_users(path: &Path, default_admin_password: &str) -> HashMap<String, UserRecord> {
    let mut map: HashMap<String, UserRecord> = if let Ok(raw) = fs::read_to_string(path) {
        serde_json::from_str::<Vec<UserRecord>>(&raw)
            .unwrap_or_default()
            .into_iter()
            .map(|u| (u.username.clone(), u))
            .collect()
    } else {
        HashMap::new()
    };

    if !map.contains_key("admin") {
        let admin = make_user("admin", default_admin_password, Role::Admin);
        map.insert("admin".to_string(), admin);
        save_users(path, &map);
    }
    map
}

pub fn save_users(path: &Path, map: &HashMap<String, UserRecord>) {
    let mut list: Vec<&UserRecord> = map.values().collect();
    list.sort_by(|a, b| a.username.cmp(&b.username));
    if let Ok(json) = serde_json::to_string_pretty(&list) {
        let tmp = path.with_extension("json.tmp");
        if fs::write(&tmp, &json).is_ok() {
            let _ = fs::rename(tmp, path);
        } else {
            let _ = fs::write(path, json);
        }
    }
}

pub fn sign_token(secret: &[u8], username: &str, role: Role) -> String {
    let claims = TokenClaims {
        sub: username.to_string(),
        role,
        exp: chrono::Utc::now().timestamp() + 7 * 24 * 3600,
    };
    let payload = URL_SAFE_NO_PAD.encode(serde_json::to_vec(&claims).unwrap_or_default());
    let mut mac = HmacSha256::new_from_slice(secret).expect("HMAC key");
    mac.update(payload.as_bytes());
    let sig = URL_SAFE_NO_PAD.encode(mac.finalize().into_bytes());
    format!("{payload}.{sig}")
}

pub fn verify_token(secret: &[u8], token: &str) -> Option<TokenClaims> {
    let (payload, sig) = token.split_once('.')?;
    let mut mac = HmacSha256::new_from_slice(secret).ok()?;
    mac.update(payload.as_bytes());
    let expected_sig = URL_SAFE_NO_PAD.decode(sig).ok()?;
    mac.verify_slice(&expected_sig).ok()?;
    let raw = URL_SAFE_NO_PAD.decode(payload).ok()?;
    let claims: TokenClaims = serde_json::from_slice(&raw).ok()?;
    if claims.exp < chrono::Utc::now().timestamp() {
        return None;
    }
    Some(claims)
}
