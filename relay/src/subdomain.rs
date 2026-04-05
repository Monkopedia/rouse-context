use rand::seq::SliceRandom;

const ADJECTIVES: &str = include_str!("words/adjectives.txt");
const NOUNS: &str = include_str!("words/nouns.txt");

pub struct SubdomainGenerator {
    adjectives: Vec<&'static str>,
    nouns: Vec<&'static str>,
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
        Self { adjectives, nouns }
    }

    /// Generate a random adjective-noun subdomain.
    pub fn generate(&self) -> String {
        let mut rng = rand::thread_rng();
        let adj = self.adjectives.choose(&mut rng).expect("no adjectives");
        let noun = self.nouns.choose(&mut rng).expect("no nouns");
        format!("{adj}-{noun}")
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
