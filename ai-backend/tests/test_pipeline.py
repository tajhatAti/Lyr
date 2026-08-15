from app.pipeline import TimedWord, align_known_lyrics, cues_to_lrc, split_known_phrases, words_to_cues


def test_audio_only_phrases_keep_vocal_gap_blank():
    words = [
        TimedWord(" প্রথম", 1.0, 1.4),
        TimedWord(" লাইন।", 1.45, 2.1, segment_break=True),
        TimedWord(" পরের", 4.0, 4.4),
        TimedWord(" লাইন", 4.45, 5.0, segment_break=True),
    ]

    cues = words_to_cues(words)

    assert [cue.text for cue in cues] == ["প্রথম লাইন।", "পরের লাইন"]
    assert cues[0].end == 2.1
    assert cues[1].start == 4.0
    assert cues_to_lrc(cues) == (
        "[00:01.00] প্রথম লাইন।\n"
        "[00:02.10]\n"
        "[00:04.00] পরের লাইন\n"
        "[00:05.00]"
    )


def test_known_lyrics_use_recognized_word_boundaries():
    recognized = [
        TimedWord(" আমি", 10.0, 10.4),
        TimedWord(" শহরে", 10.5, 11.0),
        TimedWord(" নেই", 11.1, 11.6, segment_break=True),
        TimedWord(" তুমি", 14.0, 14.4),
        TimedWord(" কোথায়", 14.5, 15.2, segment_break=True),
    ]

    cues = align_known_lyrics("আমি শহরে নেই।\nতুমি কোথায়?", recognized)

    assert len(cues) == 2
    assert cues[0].text == "আমি শহরে নেই।"
    assert cues[0].start == 10.0
    assert cues[0].end == 11.6
    assert cues[1].start == 14.0
    assert cues[1].end == 15.2


def test_phrase_split_honors_lines_punctuation_and_size_limit():
    long_line = " ".join(f"word{index}" for index in range(14))
    phrases = split_known_phrases(f"প্রথম কথা। দ্বিতীয় কথা?\n{long_line}")

    assert phrases[:2] == ["প্রথম কথা।", "দ্বিতীয় কথা?"]
    assert len(phrases[2].split()) == 12
    assert len(phrases[3].split()) == 2
