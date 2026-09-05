import os
import requests
import telebot
from telebot import types

# Bot token: prefer environment variable, fall back to hardcoded value.
BOT_TOKEN = os.environ.get("BOT_TOKEN", "8850127960:AAG9hM2eaNUwOn-U1sRmlRElVTqhhAX3S1Y")

bot = telebot.TeleBot(BOT_TOKEN)

MAX_LEN = 4000


def q(text):
    return requests.utils.quote(text)


def build_music_links(track, artist):
    """Streaming/search links for the song."""
    query = q(f"{track} {artist}".strip())
    return {
        "youtube": f"https://www.youtube.com/results?search_query={query}",
        "spotify": f"https://open.spotify.com/search/{query}",
        "google": f"https://www.google.com/search?q={q(f'{track} {artist} lyrics')}",
    }


def share_markup(track, artist):
    """Inline keyboard with listen + share options."""
    links = build_music_links(track, artist)
    markup = types.InlineKeyboardMarkup(row_width=2)

    markup.add(
        types.InlineKeyboardButton("▶️ YouTube", url=links["youtube"]),
        types.InlineKeyboardButton("🎧 Spotify", url=links["spotify"]),
    )

    # 1) Share as a Telegram message (opens chat picker with a prefilled link)
    share_text = q(f"🎵 {track} - {artist}\nLyrics via @{bot_username()}")
    share_url = f"https://t.me/share/url?url={q(links['youtube'])}&text={share_text}"
    markup.add(types.InlineKeyboardButton("📤 Share song", url=share_url))

    # 2) Forward the lyrics themselves into any chat via inline mode
    markup.add(
        types.InlineKeyboardButton(
            "💬 Send lyrics to a chat",
            switch_inline_query=f"{track} {artist}".strip(),
        )
    )
    return markup


_username_cache = {}


def bot_username():
    if "name" not in _username_cache:
        try:
            _username_cache["name"] = bot.get_me().username
        except Exception:
            _username_cache["name"] = "LyricsBot"
    return _username_cache["name"]


def search_lyrics(song_query):
    """Return (track, artist, lyrics) or None."""
    api_url = f"https://lrclib.net/api/search?q={q(song_query)}"
    response = requests.get(api_url, timeout=10)
    if response.status_code != 200:
        return None
    results = response.json()
    if not isinstance(results, list):
        return None
    for item in results:
        lyrics = item.get("plainLyrics")
        if lyrics:
            return (
                item.get("trackName") or song_query,
                item.get("artistName") or "Unknown Artist",
                lyrics,
            )
    return None


def format_lyrics(track, artist, lyrics):
    text = f"🎵 *{track}* - {artist}\n\n{lyrics}"
    if len(text) > MAX_LEN:
        text = text[: MAX_LEN - 100] + "\n\n...(লিরিক্স বড় হওয়ায় বাকি অংশ বাদ দেওয়া হয়েছে)"
    return text


@bot.message_handler(commands=["start", "help"])
def send_welcome(message):
    bot.reply_to(
        message,
        "👋 আমাকে যেকোনো গানের নাম লিখে পাঠান, আমি লিরিক্স খুঁজে দেব।\n\n"
        "📤 লিরিক্স পাওয়ার পর নিচের বাটন দিয়ে গানটি বন্ধুদের সাথে শেয়ার করতে পারবেন।",
    )


@bot.inline_handler(func=lambda query: True)
def inline_search(inline_query):
    """Inline mode: lets users share lyrics straight into any chat."""
    try:
        text = (inline_query.query or "").strip()
        if not text:
            return bot.answer_inline_query(inline_query.id, [])

        found = search_lyrics(text)
        if not found:
            return bot.answer_inline_query(inline_query.id, [])

        track, artist, lyrics = found
        result = types.InlineQueryResultArticle(
            id="1",
            title=f"{track} - {artist}",
            description=lyrics[:100].replace("\n", " "),
            input_message_content=types.InputTextMessageContent(
                format_lyrics(track, artist, lyrics), parse_mode="Markdown"
            ),
        )
        bot.answer_inline_query(inline_query.id, [result], cache_time=60)
    except Exception:
        try:
            bot.answer_inline_query(inline_query.id, [])
        except Exception:
            pass


@bot.message_handler(func=lambda message: True)
def fetch_lyrics(message):
    song_query = (message.text or "").strip()
    if not song_query:
        return

    bot.send_chat_action(message.chat.id, "typing")
    google_url = f"https://www.google.com/search?q={q(song_query + ' lyrics')}"

    try:
        found = search_lyrics(song_query)
    except Exception:
        markup = types.InlineKeyboardMarkup()
        markup.add(types.InlineKeyboardButton("🔍 Google-এ খুঁজুন", url=google_url))
        bot.reply_to(
            message,
            "⚠️ লিরিক্স সার্ভারে সংযোগ করা যায়নি। নিচের বাটনে ক্লিক করে গুগলে দেখুন:",
            reply_markup=markup,
        )
        return

    if found:
        track, artist, lyrics = found
        bot.reply_to(
            message,
            format_lyrics(track, artist, lyrics),
            parse_mode="Markdown",
            reply_markup=share_markup(track, artist),
            disable_web_page_preview=True,
        )
        return

    markup = types.InlineKeyboardMarkup()
    markup.add(types.InlineKeyboardButton("🔍 Google-এ লিরিক্স দেখুন", url=google_url))
    markup.add(
        types.InlineKeyboardButton(
            "📤 Share song",
            url=f"https://t.me/share/url?url={q(google_url)}&text={q('🎵 ' + song_query)}",
        )
    )
    bot.reply_to(
        message,
        f"❌ *{song_query}* গানটির লিরিক্স ডাটাবেজে পাওয়া যায়নি।\n\n"
        f"আপনি [এখানে ক্লিক করে]({google_url}) অথবা নিচের বাটনে চাপ দিয়ে সরাসরি গুগলে লিরিক্স দেখে নিতে পারেন।",
        parse_mode="Markdown",
        reply_markup=markup,
        disable_web_page_preview=True,
    )


if __name__ == "__main__":
    bot.infinity_polling()
