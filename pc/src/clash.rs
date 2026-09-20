//! Clashfinder, read through this machine so the key never leaves it.
//!
//! Clashfinder's API needs an account, and the public key it takes is
//! `sha256(username + privateKey)` — a shared secret. Under **ADR-0003** there is no
//! backend to hold one, and a secret shipped inside an app is a secret the first person to
//! unzip it has. So the credential lives here, on a machine somebody owns, and this face
//! is the only thing that ever sees it. That is what makes the whole source *optional*:
//! no box, no Clashfinder, and nothing else about the app changes.
//!
//! **What this is for.** Not discovery. 10,518 clashfinders were sampled on 2026-08-30 and
//! the median one was last edited the day *before* its festival, with 36% of the curated
//! ones still being edited after it had started — so a lineup is not knowable from here in
//! advance, and a **Bill** cannot be filled from it. What it is complete for is the
//! opposite end: at the festival and after it, this says which stage an act played and
//! when, which is exactly what dates an **Act**.
//!
//! **It suggests, it does not decide.** Half of all clashfinders are editable by anyone,
//! and the times are the *scheduled* ones, not the played ones. Nothing here writes a
//! **Gig**; a person reads this and confirms, the same as they would off the poster.
//!
//! Data is CC BY-NC 3.0 — perpetual and irrevocable, which is what lets it near a record
//! we promise to keep (**ADR-0005**). Attribution is a condition of that licence, so the
//! `copyright` line the payload carries is printed with the data and is not optional.

use serde::Deserialize;
use sha2::{Digest as _, Sha256};
use std::fmt::Write as _;
use std::path::{Path, PathBuf};

const HOST: &str = "https://clashfinder.com/data/event";

/// The account this machine borrows. Read from `$STS_HOME/clashfinder`, beside
/// `identity.p8`, for the same reason: it belongs to the machine and not to the repo.
pub struct Creds {
    user: String,
    key: String,
}

impl Creds {
    pub fn load(dir: &Path) -> Result<Self, String> {
        let path = dir.join("clashfinder");
        let text = std::fs::read_to_string(&path)
            .map_err(|e| format!("no clashfinder account at {}: {e}", path.display()))?;

        let mut user = None;
        let mut key = None;
        for line in text.lines() {
            let trimmed = line.trim();
            if let Some(value) = trimmed.strip_prefix("CF_USER=") {
                user = Some(value.trim().to_owned());
            } else if let Some(value) = trimmed.strip_prefix("CF_KEY=") {
                key = Some(value.trim().to_owned());
            }
        }
        match (user, key) {
            (Some(user), Some(key)) if !user.is_empty() && !key.is_empty() => Ok(Self { user, key }),
            _ => Err(format!("{} wants CF_USER= and CF_KEY= lines", path.display())),
        }
    }

    /// `authPublicKey`: sha256 of the two concatenated, no separator. Read off the key
    /// generator on the API page rather than its prose, which gives the ingredients and
    /// not the order — and a wrong order is an indistinguishable 401.
    fn public_key(&self) -> String {
        let digest = Sha256::digest(format!("{}{}", self.user, self.key).as_bytes());
        digest.iter().fold(String::new(), |mut hex, byte| {
            let _ = write!(hex, "{byte:02x}");
            hex
        })
    }
}

#[derive(Deserialize)]
struct Clashfinder {
    name: String,
    #[serde(default)]
    url: String,
    #[serde(default)]
    timezone: String,
    #[serde(default)]
    copyright: String,
    #[serde(default, rename = "lastEdit")]
    last_edit: String,
    #[serde(default)]
    locations: Vec<Location>,
}

#[derive(Deserialize)]
struct Location {
    #[serde(default)]
    name: String,
    #[serde(default)]
    events: Vec<Act>,
}

#[derive(Deserialize)]
struct Act {
    #[serde(default)]
    name: String,
    #[serde(default)]
    start: String,
    #[serde(default)]
    end: String,
    /// Present on about one act in fifty, so it resolves nothing on its own — but where it
    /// is there it is the only thing in the payload that ties a typed name to `MusicBrainz`.
    ///
    /// The payload carries **both** `mbId` and `mbid`, on the same act, with the same
    /// value. Reading one and ignoring the other is deliberate: `alias` makes serde see
    /// one field given twice and reject the whole document, which is how this was found.
    #[serde(default, rename = "mbId")]
    mb_id: String,
}

/// A slot on the timetable, flattened out of the by-stage nesting the payload arrives in
/// because a night is read down the clock, not down one stage.
struct Slot {
    start: String,
    end: String,
    stage: String,
    act: String,
    mb_id: String,
}

/// Ids come off a URL and go into a path and a query string, so they are checked rather
/// than trusted. Clashfinder's own ids are this alphabet.
fn is_safe_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 64
        && id
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

pub fn fetch(dir: &Path, id: &str) -> Result<String, String> {
    if !is_safe_id(id) {
        return Err(format!("{id} is not a clashfinder id (letters, digits, - and _)"));
    }
    let creds = Creds::load(dir)?;
    let url = format!(
        "{HOST}/{id}.json?authUsername={}&authPublicKey={}",
        creds.user,
        creds.public_key()
    );
    let body = ureq::get(&url)
        .call()
        .map_err(|e| format!("clashfinder said no: {e}"))?
        .body_mut()
        .read_to_string()
        .map_err(|e| format!("unreadable response: {e}"))?;

    // Kept because it is the thing to look at when the printed table looks wrong, and
    // because the licence permits keeping it. Best-effort: a cache that cannot be written
    // is not a reason to throw away the answer already in hand.
    let cached = cache_path(dir, id);
    if let Some(parent) = cached.parent() {
        let _ = std::fs::create_dir_all(parent);
        let _ = std::fs::write(&cached, &body);
    }
    Ok(body)
}

fn cache_path(dir: &Path, id: &str) -> PathBuf {
    dir.join("clashfinder-cache").join(format!("{id}.json"))
}

pub fn show(dir: &Path, id: &str) {
    let body = match fetch(dir, id) {
        Ok(body) => body,
        Err(e) => {
            println!("{e}");
            return;
        }
    };
    match serde_json::from_str::<Clashfinder>(&body) {
        Ok(cf) => print_timetable(&cf),
        Err(e) => println!("not a clashfinder document: {e}"),
    }
}

fn print_timetable(cf: &Clashfinder) {
    let mut slots: Vec<Slot> = cf
        .locations
        .iter()
        .flat_map(|location| {
            location.events.iter().map(move |act| Slot {
                start: act.start.clone(),
                end: act.end.clone(),
                stage: location.name.clone(),
                act: act.name.clone(),
                mb_id: act.mb_id.clone(),
            })
        })
        .collect();
    // "YYYY-MM-DD HH:MM" sorts as text exactly as it sorts as time, which is the whole
    // reason to leave these as strings: no parse, no timezone to get wrong, and the
    // payload's own `tzOffset` note says these are already local to the event.
    slots.sort_by(|a, b| a.start.cmp(&b.start).then_with(|| a.stage.cmp(&b.stage)));

    println!("{}", cf.name);
    if !cf.timezone.is_empty() {
        println!("{}  ·  times are local to the event", cf.timezone);
    }
    if !cf.last_edit.is_empty() {
        println!("last edited {} — a clashfinder is edited up to and past the doors", cf.last_edit);
    }

    let mut day = String::new();
    for slot in &slots {
        let today = slot.start.split_whitespace().next().unwrap_or_default();
        if today != day {
            today.clone_into(&mut day);
            println!("\n{day}");
        }
        println!(
            "  {:<5} {:<5} {:<22} {}{}",
            slot.start.split_whitespace().nth(1).unwrap_or_default(),
            slot.end.split_whitespace().nth(1).unwrap_or_default(),
            slot.stage,
            slot.act,
            if slot.mb_id.is_empty() { "" } else { " ♪" },
        );
    }

    println!("\n{} acts across {} stages", slots.len(), cf.locations.len());
    if !cf.url.is_empty() {
        println!("{}", cf.url);
    }
    // A condition of the licence, not a courtesy.
    if !cf.copyright.is_empty() {
        println!("{}", cf.copyright);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn public_key_matches_the_generator_on_the_api_page() {
        // The one vector we have: the worked example the API page's own generator prints
        // for these inputs. If the concatenation order ever drifts this fails here rather
        // than as a 401 nobody can read.
        let creds = Creds {
            user: "dizzi90".to_owned(),
            key: "wl84oksn3jhgsnui".to_owned(),
        };
        assert!(creds
            .public_key()
            .starts_with("16f87d3935c0db8c69033ddfeea299eaf2ab7f172559d9781acce9a"));
    }

    #[test]
    fn ids_out_of_a_url_cannot_walk_out_of_the_cache_directory() {
        assert!(is_safe_id("eotr2026"));
        assert!(is_safe_id("the_fest-24"));
        assert!(!is_safe_id("../../identity.p8"));
        assert!(!is_safe_id("a b"));
        assert!(!is_safe_id(""));
    }

    #[test]
    fn a_timetable_is_read_down_the_clock_and_not_down_one_stage() {
        let doc = r#"{
          "name": "Test 2026", "timezone": "Europe/London", "copyright": "CC BY-NC 3.0",
          "locations": [
            {"name": "Woods", "events": [
              {"name": "Geese", "start": "2026-09-03 21:15", "end": "2026-09-03 22:45"}]},
            {"name": "Garden", "events": [
              {"name": "Opener", "start": "2026-09-03 16:00", "end": "2026-09-03 16:45"},
              {"name": "Next Day", "start": "2026-09-04 12:00", "end": "2026-09-04 12:45"}]}
          ]}"#;
        let cf: Clashfinder = serde_json::from_str(doc).unwrap();
        let mut slots: Vec<_> = cf
            .locations
            .iter()
            .flat_map(|l| l.events.iter().map(|a| (a.start.clone(), a.name.clone())))
            .collect();
        slots.sort();
        let order: Vec<&str> = slots.iter().map(|(_, name)| name.as_str()).collect();
        assert_eq!(order, vec!["Opener", "Geese", "Next Day"]);
    }
}
