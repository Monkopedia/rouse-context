package com.rousecontext.tunnel

import kotlin.random.Random

/**
 * Generates integration secrets in the format "{adjective}-{integration}".
 *
 * The client generates these locally and sends the list of values to the relay.
 * The relay stores them as valid secret prefixes without knowing which integration
 * each maps to.
 */
object SecretGenerator {

    /**
     * Generate a secret for a single integration.
     * Format: "{random-adjective}-{integrationId}"
     */
    fun generate(integrationId: String, random: Random = Random): String {
        val adjective = ADJECTIVES[random.nextInt(ADJECTIVES.size)]
        return "$adjective-$integrationId"
    }

    /**
     * Generate secrets for a list of integration IDs.
     * Returns a map of integrationId to secret.
     */
    fun generateAll(integrationIds: List<String>, random: Random = Random): Map<String, String> =
        integrationIds.associateWith { generate(it, random) }

    @Suppress("MaxLineLength")
    internal val ADJECTIVES = listOf(
        "able", "aged", "airy", "apt", "avid",
        "bare", "bold", "brave", "brief", "bright",
        "brisk", "broad", "calm", "chief", "civil",
        "clean", "clear", "close", "cold", "cool",
        "crisp", "cute", "dark", "dear", "deep",
        "dense", "dim", "dire", "dry", "dual",
        "dull", "dusk", "each", "eager", "early",
        "east", "easy", "edgy", "epic", "equal",
        "even", "ever", "evil", "exact", "extra",
        "faint", "fair", "fast", "fat", "few",
        "final", "fine", "firm", "first", "fit",
        "flat", "fond", "foul", "free", "fresh",
        "full", "fun", "glad", "gold", "good",
        "grand", "gray", "great", "green", "grim",
        "half", "happy", "hard", "harsh", "high",
        "holy", "hot", "huge", "idle", "ill",
        "inner", "iron", "just", "keen", "key",
        "kind", "known", "large", "last", "late",
        "lazy", "lean", "left", "light", "likely",
        "live", "local", "lone", "long", "lost",
        "loud", "low", "lucky", "mad", "magic",
        "main", "major", "merry", "mild", "minor",
        "misty", "mixed", "moral", "much", "mute",
        "naive", "naval", "near", "neat", "new",
        "next", "nice", "noble", "north", "noted",
        "novel", "odd", "old", "only", "open",
        "outer", "oval", "pale", "past", "peak",
        "plain", "plush", "polar", "poor", "prime",
        "proud", "pure", "quick", "quiet", "rapid",
        "rare", "raw", "ready", "real", "rich",
        "right", "rigid", "ripe", "roman", "rough",
        "round", "royal", "rude", "rural", "safe",
        "sharp", "sheer", "short", "shy", "silver",
        "slim", "slow", "small", "smart", "snug",
        "soft", "solar", "sole", "solid", "south",
        "spare", "stark", "steep", "stern", "stiff",
        "stone", "stout", "super", "sure", "sweet",
        "swift", "tall", "tame", "thick", "thin",
        "third", "tidy", "tight", "tiny", "top",
        "total", "tough", "true", "upper", "urban",
        "used", "usual", "vague", "valid", "vast",
        "vivid", "vocal", "warm", "weak", "west",
        "white", "whole", "wide", "wild", "wise",
        "young", "zesty"
    )
}
