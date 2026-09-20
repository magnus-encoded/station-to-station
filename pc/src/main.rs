//! Station to Station, the PC face.
//!
//! Not a third app. This face has no **Line**, no **Room** and no **Flyover** — it is a
//! **Contact** that stands still: it gets a key into a phone the way another phone does,
//! then answers on the LAN and takes what is offered. Two commands is the whole of it.
//!
//! Layout follows the rule the other two faces already follow (ADR-0017): native at the
//! face — a terminal here, because that is what this machine has — identical in between,
//! which is the wire in `session.rs` and the card in `pair.rs`. Neither of those invents
//! anything; both are the Kotlin read back in Rust.

mod clash;
mod identity;
mod pair;
mod session;

use identity::Identity;
use std::io::{BufRead as _, Write as _};
use std::path::PathBuf;
use std::process::ExitCode;

fn main() -> ExitCode {
    let name = std::env::args()
        .skip(1)
        .find_map(|arg| arg.strip_prefix("--name=").map(str::to_owned))
        .unwrap_or_else(default_name);
    let dir = state_dir();

    let identity = match Identity::load_or_create(&dir, &name) {
        Ok(loaded) => loaded,
        Err(e) => {
            eprintln!("cannot open {}: {e}", dir.display());
            return ExitCode::FAILURE;
        }
    };

    println!("station to station — {}", identity.name);
    println!("{}", dir.display());
    whoami(&identity);
    println!();
    help();

    repl(&identity);
    ExitCode::SUCCESS
}

fn repl(identity: &Identity) {
    let stdin = std::io::stdin();
    loop {
        print!("> ");
        let _ = std::io::stdout().flush();
        let mut line = String::new();
        // EOF is a quit, and so is a read that fails: there is no interactive session
        // left to prompt into either way.
        if stdin.lock().read_line(&mut line).unwrap_or(0) == 0 {
            return;
        }
        match line.trim() {
            "" => {}
            "pair" => do_pair(identity),
            "listen" => {
                if let Err(e) = session::listen(identity, &|message| println!("{message}")) {
                    println!("stopped: {e}");
                }
            }
            "contacts" => contacts(identity),
            "whoami" => whoami(identity),
            "help" | "?" => help(),
            "quit" | "exit" => return,
            "clash" => println!("clash <id> — the id is in the clashfinder's URL, after /s/"),
            other => match other.strip_prefix("clash ") {
                Some(id) => clash::show(&state_dir(), id.trim()),
                None => println!("no such command: {other}  (try `help`)"),
            },
        }
    }
}

fn help() {
    println!("pair      become a Contact — needs the phone's Exchange screen open, and this");
    println!("          machine in the room with it");
    println!("listen    advertise on the LAN and receive whatever a Contact offers");
    println!("contacts  who this machine can be reached by");
    println!("clash <id>  a festival's timetable off clashfinder, with the account key kept");
    println!("          on this machine — what dates an Act, never what fills a Bill");
    println!("whoami    this machine's public key, as a phone sees it");
    println!("quit");
}

/// Enough of the key to recognise it beside the one on the phone, and not so much that it
/// wraps. `chars().skip().take()` rather than a slice: a byte range into a string is a
/// panic waiting for the first non-ASCII input, even when this particular string is base64.
///
/// **The window starts at 36, and that is the whole point of this function.** A P-256
/// `SubjectPublicKeyInfo` begins with a fixed DER algorithm header, and base64 of it is
/// the same 36 characters for every key ever generated — `MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE`.
/// Taking from the front showed that header and nothing else, so every key printed
/// identically: `whoami` matched every contact, and a genuine impersonation would have
/// looked correct beside the real thing. Anything shorter than the header abbreviates to
/// nothing, which is the honest rendering of a key that cannot be one.
const KEY_HEADER_CHARS: usize = 36;

fn abbreviate(key: &str) -> String {
    key.chars().skip(KEY_HEADER_CHARS).take(16).collect()
}

fn whoami(identity: &Identity) {
    println!("key {}…", abbreviate(identity.public_key_base64()));
    println!("{} contact(s)", identity.contacts().len());
}

fn contacts(identity: &Identity) {
    let all = identity.contacts();
    if all.is_empty() {
        println!("nobody yet — `pair` with a phone showing its Exchange screen");
        return;
    }
    for contact in all {
        println!(
            "{:<24} {}…  {}",
            contact.name,
            abbreviate(&contact.public_key),
            contact.setlistfm.unwrap_or_default()
        );
    }
}

fn do_pair(identity: &Identity) {
    // One runtime per pairing rather than one for the process: the LAN half is blocking
    // std sockets and has no use for it, and a pairing is a thing you do once.
    let runtime = match tokio::runtime::Runtime::new() {
        Ok(runtime) => runtime,
        Err(e) => {
            println!("no async runtime: {e}");
            return;
        }
    };
    println!("scanning for a phone with the Exchange screen open…");
    let found = match runtime.block_on(pair::scan()) {
        Ok(found) => found,
        Err(e) => {
            println!("scan failed: {e}");
            return;
        }
    };
    if found.is_empty() {
        println!("nothing advertising the service. Is the Exchange screen open?");
        return;
    }
    for (index, peer) in found.iter().enumerate() {
        println!("  {}. {}", index.saturating_add(1), peer.address);
    }

    let Some(target) = choose(&found) else {
        println!("no such peer");
        return;
    };
    match runtime.block_on(pair::exchange(target, identity)) {
        Ok(contact) => {
            let name = contact.name.clone();
            match identity.remember(contact) {
                Ok(true) => println!("{name} is a Contact now, and holds this machine's key"),
                Ok(false) => println!("{name} was already a Contact — nothing changed"),
                Err(e) => println!("could not write contacts: {e}"),
            }
        }
        Err(e) => println!("exchange failed: {e}"),
    }
}

fn choose(found: &[pair::Found]) -> Option<&pair::Found> {
    print!("which? [1] ");
    let _ = std::io::stdout().flush();
    let mut choice = String::new();
    let _ = std::io::stdin().read_line(&mut choice);
    let ordinal = choice.trim().parse::<usize>().unwrap_or(1);
    found.get(ordinal.saturating_sub(1))
}

fn state_dir() -> PathBuf {
    std::env::var_os("STS_HOME").map_or_else(
        || home().join(".station-to-station"),
        PathBuf::from,
    )
}

fn default_name() -> String {
    std::fs::read_to_string("/etc/hostname")
        .map(|text| text.trim().to_owned())
        .ok()
        .filter(|name| !name.is_empty())
        .unwrap_or_else(|| "pc".to_owned())
}

fn home() -> PathBuf {
    std::env::var_os("HOME").map_or_else(|| PathBuf::from("."), PathBuf::from)
}
