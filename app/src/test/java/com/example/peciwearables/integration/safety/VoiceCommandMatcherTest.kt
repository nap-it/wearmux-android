package com.example.peciwearables.integration.safety

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandMatcherTest {

    // ── matchesCrossing ───────────────────────────────────────────────

    @Test fun crossing_exact_matches() =
        assertTrue(VoiceCommandMatcher.matchesCrossing("crossing"))

    @Test fun cross_short_matches() =
        assertTrue(VoiceCommandMatcher.matchesCrossing("cross"))

    @Test fun crossed_matches() =
        assertTrue(VoiceCommandMatcher.matchesCrossing("I crossed the road"))

    @Test fun asr_typo_crosing_matches() =
        assertTrue(VoiceCommandMatcher.matchesCrossing("crosing"))

    @Test fun crossing_in_sentence_matches() =
        assertTrue(VoiceCommandMatcher.matchesCrossing("I am crossing now"))

    @Test fun not_crossing_negation_blocked() =
        assertFalse(VoiceCommandMatcher.matchesCrossing("not crossing"))

    @Test fun dont_cross_negation_blocked() =
        assertFalse(VoiceCommandMatcher.matchesCrossing("don't cross"))

    @Test fun unrelated_text_no_match() =
        assertFalse(VoiceCommandMatcher.matchesCrossing("hello world how are you"))

    @Test fun crossroads_not_matched() =
        assertFalse(VoiceCommandMatcher.matchesCrossing("turn at the crossroads"))

    @Test fun empty_string_no_match() =
        assertFalse(VoiceCommandMatcher.matchesCrossing(""))

    @Test fun pt_atravessar_matches() =
        assertTrue(VoiceCommandMatcher.matchesCrossing("vou atravessar"))

    // ── matchesStop ───────────────────────────────────────────────────

    @Test fun stop_exact_matches() =
        assertTrue(VoiceCommandMatcher.matchesStop("stop"))

    @Test fun stopped_matches() =
        assertTrue(VoiceCommandMatcher.matchesStop("I stopped"))

    @Test fun asr_typo_stopp_matches() =
        assertTrue(VoiceCommandMatcher.matchesStop("stopp"))

    @Test fun not_stop_negation_blocked() =
        assertFalse(VoiceCommandMatcher.matchesStop("do not stop the music"))

    @Test fun pt_para_matches() =
        assertTrue(VoiceCommandMatcher.matchesStop("para"))

    @Test fun pt_pare_matches() =
        assertTrue(VoiceCommandMatcher.matchesStop("pare"))

    @Test fun bus_stop_sentence_matches_stop() =
        assertTrue(VoiceCommandMatcher.matchesStop("the bus stop is nearby"))

    @Test fun empty_stop_no_match() =
        assertFalse(VoiceCommandMatcher.matchesStop(""))
}
