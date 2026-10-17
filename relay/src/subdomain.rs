use rand::seq::SliceRandom;
use rand::Rng;
use std::collections::VecDeque;
use std::sync::Mutex;

const ADJECTIVES: &str = include_str!("words/adjectives.txt");
const NOUNS: &str = include_str!("words/nouns.txt");

pub struct SubdomainGenerator {
    adjectives: Vec<&'static str>,
    nouns: Vec<&'static str>,
    /// Names [`Self::generate`] returns, in order, before falling back to
    /// random picks. Always `None` in production; set only via
    /// [`Self::scripted`] so tests can drive specific names deterministically.
    script: Option<Mutex<VecDeque<String>>>,
}

impl SubdomainGenerator {
    pub fn new() -> Self {
        let adjectives: Vec<&str> = ADJECTIVES
            .lines()
            .map(|l| l.trim())
            .filter(|l| !l.is_empty())
            .collect();
        let nouns: Vec<&str> = NOUNS
            .lines()
            .map(|l| l.trim())
            .filter(|l| !l.is_empty())
            .collect();
        Self {
            adjectives,
            nouns,
            script: None,
        }
    }

    /// A generator whose [`Self::generate`] returns `names` in order, then
    /// falls back to random adjective-noun picks once the list is used up.
    /// Test seam only.
    #[doc(hidden)]
    pub fn scripted<I, S>(names: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: Into<String>,
    {
        let mut generator = Self::new();
        generator.script = Some(Mutex::new(names.into_iter().map(Into::into).collect()));
        generator
    }

    /// Generate a random adjective-noun subdomain.
    pub fn generate(&self) -> String {
        if let Some(script) = &self.script {
            if let Some(next) = script.lock().unwrap_or_else(|e| e.into_inner()).pop_front() {
                return next;
            }
        }
        let mut rng = rand::thread_rng();
        let adj = self.adjectives.choose(&mut rng).expect("no adjectives");
        let noun = self.nouns.choose(&mut rng).expect("no nouns");
        format!("{adj}-{noun}")
    }

    /// Generate a single-word subdomain drawn uniformly from the union of
    /// adjectives and nouns.
    ///
    /// This is the preferred tier for `POST /request-subdomain`: single-word
    /// names are dramatically easier to remember/read aloud. The
    /// `adjective-noun` combined pool (see [`Self::generate`]) is kept as the
    /// overflow fallback once a single-word pick collides with an existing
    /// device or reservation.
    pub fn generate_single_word(&self) -> String {
        let total = self.adjectives.len() + self.nouns.len();
        assert!(total > 0, "single-word pool is empty");
        let mut rng = rand::thread_rng();
        let idx = rng.gen_range(0..total);
        if idx < self.adjectives.len() {
            self.adjectives[idx].to_string()
        } else {
            self.nouns[idx - self.adjectives.len()].to_string()
        }
    }

    /// Size of the single-word pool (adjectives ∪ nouns).
    pub fn single_word_pool_size(&self) -> usize {
        self.adjectives.len() + self.nouns.len()
    }

    /// Return a copy of the adjective list. Exposed for tests that need to
    /// verify membership of generated names in the configured pool.
    pub fn adjectives(&self) -> &[&'static str] {
        &self.adjectives
    }

    /// Return a copy of the noun list. Exposed for tests that need to verify
    /// membership of generated names in the configured pool.
    pub fn nouns(&self) -> &[&'static str] {
        &self.nouns
    }

    /// Return (adjective_count, noun_count) for testing word list sizes.
    pub fn word_counts(&self) -> (usize, usize) {
        (self.adjectives.len(), self.nouns.len())
    }
}

impl Default for SubdomainGenerator {
    fn default() -> Self {
        Self::new()
    }
}
