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
fn generate_integration_secrets_format() {
    let gen = SubdomainGenerator::new();
    let integrations = vec![
        "health".to_string(),
        "outreach".to_string(),
        "notifications".to_string(),
    ];
    let secrets = gen.generate_integration_secrets(&integrations);

    assert_eq!(secrets.len(), 3);
    for name in &integrations {
        let secret = secrets
            .get(name)
            .expect(&format!("missing secret for {name}"));
        // Must end with -{integration_name}
        assert!(
            secret.ends_with(&format!("-{name}")),
            "Secret '{secret}' should end with '-{name}'"
        );
        // Must have exactly one hyphen separating adjective from integration name
        let parts: Vec<&str> = secret.splitn(2, '-').collect();
        assert_eq!(
            parts.len(),
            2,
            "Secret '{secret}' should be adjective-integration format"
        );
        assert!(
            !parts[0].is_empty(),
            "Adjective part is empty in '{secret}'"
        );
    }
}

#[test]
fn generate_adjective_returns_valid_word() {
    let gen = SubdomainGenerator::new();
    for _ in 0..50 {
        let adj = gen.generate_adjective();
        assert!(!adj.is_empty(), "Adjective should not be empty");
        assert_eq!(adj, adj.to_lowercase(), "Adjective not lowercase: {adj}");
        for ch in adj.chars() {
            assert!(
                ch.is_ascii_lowercase(),
                "Invalid char '{ch}' in adjective: {adj}"
            );
        }
    }
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
