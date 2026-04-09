use rouse_relay::subdomain::SubdomainGenerator;
use std::collections::HashSet;

#[test]
fn generated_subdomain_matches_format() {
    let gen = SubdomainGenerator::new();
    let name = gen.generate();
    // Must be adjective-noun format: two parts separated by hyphen
    let parts: Vec<&str> = name.split('-').collect();
    assert_eq!(parts.len(), 2, "Expected adjective-noun, got: {name}");
    assert!(!parts[0].is_empty(), "Adjective part is empty");
    assert!(!parts[1].is_empty(), "Noun part is empty");
}

#[test]
fn generated_subdomain_is_lowercase() {
    let gen = SubdomainGenerator::new();
    for _ in 0..50 {
        let name = gen.generate();
        assert_eq!(name, name.to_lowercase(), "Subdomain not lowercase: {name}");
    }
}

#[test]
fn generated_subdomain_is_dns_safe() {
    let gen = SubdomainGenerator::new();
    for _ in 0..100 {
        let name = gen.generate();
        // DNS labels: a-z, 0-9, hyphens. Must not start/end with hyphen.
        // Max 63 chars per label.
        assert!(name.len() <= 63, "Subdomain too long: {name}");
        assert!(
            !name.starts_with('-'),
            "Subdomain starts with hyphen: {name}"
        );
        assert!(!name.ends_with('-'), "Subdomain ends with hyphen: {name}");
        for ch in name.chars() {
            assert!(
                ch.is_ascii_lowercase() || ch.is_ascii_digit() || ch == '-',
                "Invalid DNS char '{ch}' in subdomain: {name}"
            );
        }
    }
}

#[test]
fn no_collision_in_1000_generations() {
    let gen = SubdomainGenerator::new();
    let mut seen = HashSet::new();
    let mut collision_count = 0;
    for _ in 0..1000 {
        let name = gen.generate();
        if !seen.insert(name) {
            collision_count += 1;
        }
    }
    // With ~200 adjectives * ~200 nouns = ~40,000 combinations,
    // the birthday paradox predicts ~12 collisions in 1000 draws.
    // We allow up to 30 as a generous tolerance (still catches
    // degenerate word lists).
    assert!(
        collision_count <= 30,
        "Too many collisions ({collision_count}) in 1000 generations"
    );
}

#[test]
fn word_lists_have_sufficient_entries() {
    let gen = SubdomainGenerator::new();
    let (adj_count, noun_count) = gen.word_counts();
    assert!(
        adj_count >= 100,
        "Need at least 100 adjectives, got {adj_count}"
    );
    assert!(
        noun_count >= 100,
        "Need at least 100 nouns, got {noun_count}"
    );
}
