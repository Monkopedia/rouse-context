//! Short adjective list used to prefix user-visible integration secrets.
//!
//! Secrets are emitted in the form `{adjective}-{integrationId}` (for example
//! `brave-outreach`). The list is intentionally short and biased toward common,
//! readable English adjectives so that URLs like
//! `brave-outreach.abc123.rousecontext.com` are easy to read and say aloud.
//!
//! This is the authoritative copy of the list — the Android client used to
//! keep its own copy for local generation, but secret generation now happens
//! exclusively on the relay (see `relay/src/api/register.rs`).

use rand::seq::SliceRandom;
use rand::Rng;

/// Curated list of ~200 short, readable adjectives.
pub const ADJECTIVES: &[&str] = &[
    "able", "aged", "airy", "apt", "avid", "bare", "bold", "brave", "brief", "bright", "brisk",
    "broad", "calm", "chief", "civil", "clean", "clear", "close", "cold", "cool", "crisp", "cute",
    "dark", "dear", "deep", "dense", "dim", "dire", "dry", "dual", "dull", "dusk", "each", "eager",
    "early", "east", "easy", "edgy", "epic", "equal", "even", "ever", "evil", "exact", "extra",
    "faint", "fair", "fast", "fat", "few", "final", "fine", "firm", "first", "fit", "flat", "fond",
    "foul", "free", "fresh", "full", "fun", "glad", "gold", "good", "grand", "gray", "great",
    "green", "grim", "half", "happy", "hard", "harsh", "high", "holy", "hot", "huge", "idle",
    "ill", "inner", "iron", "just", "keen", "key", "kind", "known", "large", "last", "late",
    "lazy", "lean", "left", "light", "likely", "live", "local", "lone", "long", "lost", "loud",
    "low", "lucky", "mad", "magic", "main", "major", "merry", "mild", "minor", "misty", "mixed",
    "moral", "much", "mute", "naive", "naval", "near", "neat", "new", "next", "nice", "noble",
    "north", "noted", "novel", "odd", "old", "only", "open", "outer", "oval", "pale", "past",
    "peak", "plain", "plush", "polar", "poor", "prime", "proud", "pure", "quick", "quiet", "rapid",
    "rare", "raw", "ready", "real", "rich", "right", "rigid", "ripe", "roman", "rough", "round",
    "royal", "rude", "rural", "safe", "sharp", "sheer", "short", "shy", "silver", "slim", "slow",
    "small", "smart", "snug", "soft", "solar", "sole", "solid", "south", "spare", "stark", "steep",
    "stern", "stiff", "stone", "stout", "super", "sure", "sweet", "swift", "tall", "tame", "thick",
    "thin", "third", "tidy", "tight", "tiny", "top", "total", "tough", "true", "upper", "urban",
    "used", "usual", "vague", "valid", "vast", "vivid", "vocal", "warm", "weak", "west", "white",
    "whole", "wide", "wild", "wise", "young", "zesty",
];

/// Pick one adjective uniformly at random using the provided RNG.
pub fn pick_random(rng: &mut impl Rng) -> &'static str {
    ADJECTIVES
        .choose(rng)
        .copied()
        .expect("ADJECTIVES list is non-empty")
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;

    #[test]
    fn list_has_expected_size_and_no_duplicates() {
        assert!(
            ADJECTIVES.len() >= 200,
            "expected at least 200 adjectives, got {}",
            ADJECTIVES.len()
        );
        let unique: HashSet<&&str> = ADJECTIVES.iter().collect();
        assert_eq!(
            unique.len(),
            ADJECTIVES.len(),
            "ADJECTIVES contains duplicates"
        );
    }

    #[test]
    fn pick_random_returns_member_of_list() {
        let mut rng = rand::thread_rng();
        for _ in 0..50 {
            let picked = pick_random(&mut rng);
            assert!(
                ADJECTIVES.contains(&picked),
                "picked {picked:?} not in ADJECTIVES"
            );
        }
    }
}
