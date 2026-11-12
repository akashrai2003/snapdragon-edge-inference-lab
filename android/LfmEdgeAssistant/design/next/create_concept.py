"""Generate a code-native design board. These are proposed screens, not app screenshots."""

from html import escape
from pathlib import Path

out = []


def e(s):
    out.append(s)


def rect(x, y, w, h, c, r=0, stroke=None):
    e(
        f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{c}"'
        + (f' stroke="{stroke}"' if stroke else "")
        + "/>"
    )


def text(x, y, s, size=14, c="#29242E", weight=400, font="sans-serif"):
    e(
        f'<text x="{x}" y="{y}" fill="{c}" font-family="{font}" font-size="{size}" font-weight="{weight}">{escape(s)}</text>'
    )


def spark(x, y, size=16, c="#7554A4"):
    e(
        f'<path d="M {x} {y - size} Q {x + size * 0.2} {y - size * 0.2} {x + size} {y} Q {x + size * 0.2} {y + size * 0.2} {x} {y + size} Q {x - size * 0.2} {y + size * 0.2} {x - size} {y} Q {x - size * 0.2} {y - size * 0.2} {x} {y - size}Z" fill="{c}"/>'
    )


def mic(x, y, c="#FFFFFF"):
    e(
        f'<g fill="none" stroke="{c}" stroke-width="1.8" stroke-linecap="round"><rect x="{x - 3}" y="{y - 9}" width="6" height="12" rx="3"/><path d="M {x - 7} {y - 1}q 0 10 7 10q 7 0 7 -10 M{x} {y + 9}v4 M{x - 4} {y + 13}h8"/></g>'
    )


def action(x, y, label="↑", fill="#29242E"):
    rect(x, y, 42, 42, fill, 21)
    if label == "mic":
        mic(x + 21, y + 20)
    else:
        text(x + 14, y + 28, label, 23, "#FFFFFF")


def composer(x, y, w=276, placeholder="Ask anything…", mode="mic"):
    rect(x, y, w, 58, "#FFFFFF", 29, "#E4DFE8")
    text(x + 18, y + 35, placeholder, 14, "#89808C")
    action(x + w - 50, y + 8, mode)


def pill(x, y, w, label, fill="#ECE5F4", color="#64428D"):
    rect(x, y, w, 36, fill, 18)
    text(x + 13, y + 23, label, 12, color, 500)


def phone(x, label):
    text(x, 173, label, 15, "#514A56", 600)
    rect(x, 195, 328, 668, "#29242E", 34)
    rect(x + 5, 200, 318, 658, "#F8F6F1", 30)
    text(x + 23, 231, "9:41", 11, "#29242E", 600)
    text(x + 259, 231, "•••", 11)
    rect(x + 129, 844, 70, 4, "#29242E", 2)
    return x + 5, 200


e("""<svg xmlns="http://www.w3.org/2000/svg" width="1880" height="1120" viewBox="0 0 1880 1120">
<defs>
<linearGradient id="art" x2="1" y2="1"><stop stop-color="#EBE0F5"/><stop offset=".55" stop-color="#D9C5EC"/><stop offset="1" stop-color="#F1DFD5"/></linearGradient>
<radialGradient id="orb"><stop stop-color="#F9E9E0"/><stop offset=".5" stop-color="#D5B5E5"/><stop offset="1" stop-color="#8D6AB3"/></radialGradient>
</defs>""")
rect(0, 0, 1880, 1120, "#EEEAE3")
text(48, 52, "LFM / A little help, right when you need it.", 30, "#29242E", 600)
text(
    48,
    84,
    "PROPOSED DESIGN · App, floating assistant, voice and widget · 12 November 2026",
    13,
    "#776F7C",
)
text(
    48,
    118,
    "Warm ivory. Soft lilac. Clear actions. No hardware jargon in everyday screens.",
    16,
    "#645C68",
)
# Home
x, y = phone(48, "01 / HOME")
spark(x + 28, y + 64, 12)
text(x + 48, y + 72, "lfm", 25, weight=600)
pill(x + 224, y + 47, 75, "• Ready", "#E5EADD", "#526143")
text(x + 22, y + 123, "GOOD EVENING", 10, "#807587", 600)
text(x + 22, y + 163, "Make room", 31, font="serif")
text(x + 22, y + 201, "for a good idea.", 31, font="serif")
text(x + 22, y + 231, "A fresh perspective, whenever you need it.", 12, "#807587")
rect(x + 20, y + 255, 278, 176, "url(#art)", 24)
for i in range(10):
    e(
        f'<ellipse cx="{x + 159}" cy="{y + 326}" rx="27" ry="53" transform="rotate({i * 36} {x + 159} {y + 343})" fill="url(#orb)" opacity=".5"/>'
    )
e(
    f'<ellipse cx="{x + 159}" cy="{y + 343}" rx="117" ry="44" transform="rotate(-20 {x + 159} {y + 343})" fill="none" stroke="#A88ABD" stroke-width=".8"/>'
)
spark(x + 57, y + 295, 9)
spark(x + 265, y + 385, 7)
text(x + 60, y + 409, "FOLLOW A LITTLE CURIOSITY", 9, "#705584")
rect(x + 20, y + 447, 278, 54, "#29242E", 27)
text(x + 41, y + 480, "Ask anything", 15, "#FFFFFF", 500)
text(x + 261, y + 481, "↗", 24, "#D7BDED")
text(x + 22, y + 534, "Start somewhere", 15, weight=600)
rect(x + 20, y + 549, 134, 68, "#F0E1D6", 18)
spark(x + 39, y + 570, 7, "#AA6B49")
text(x + 33, y + 599, "A fresh idea  ↗", 12)
rect(x + 164, y + 549, 134, 68, "#E4E9DE", 18)
text(x + 178, y + 576, "≈", 21, "#657653")
text(x + 177, y + 599, "Make it simple ↗", 12)
text(x + 44, y + 642, "Conversations", 11, "#786E7D")
text(x + 209, y + 642, "Settings", 11, "#786E7D")
# Full chat
x, y = phone(412, "02 / CHAT")
text(x + 22, y + 69, "‹", 26)
spark(x + 64, y + 63, 10)
text(x + 83, y + 70, "lfm", 24, weight=600)
text(x + 279, y + 69, "+", 24)
text(x + 22, y + 119, "A little perspective", 12, "#807587")
rect(x + 65, y + 145, 233, 72, "#EAE1F1", 19)
text(x + 81, y + 172, "How do I make a busy day", 13)
text(x + 81, y + 194, "feel a little less rushed?", 13)
spark(x + 34, y + 254, 9)
text(x + 22, y + 289, "Start with a little breathing room.", 15, weight=500)
for j, line in enumerate(
    [
        "Choose one thing that matters most.",
        "Give it twenty quiet minutes before",
        "you open the rest of your day.",
    ]
):
    text(x + 22, y + 322 + j * 23, line, 13, "#645C68")
rect(x + 20, y + 399, 278, 82, "#ECE5F4", 19)
text(x + 36, y + 426, "TRY THIS", 9, "#7554A4", 600)
text(x + 36, y + 451, "“Today feels lighter if I finish…”", 13)
pill(x + 20, y + 501, 77, "Copy", "#ECE8E2", "#645C68")
pill(x + 106, y + 501, 111, "Read aloud", "#ECE8E2", "#645C68")
composer(x + 20, y + 565)
text(x + 95, y + 643, "Just you & your ideas.", 10, "#89808C")
# Floating helper background
for i, label in enumerate(["03 / QUICK ASK", "04 / LISTENING", "05 / RESPONSE"]):
    x, y = phone(776 + i * 364, label)
    rect(x, y + 45, 318, 581, "#D8DED2", 0)
    text(x + 25, y + 101, "Your day.", 33, "#5C6B54", 400, "serif")
    text(x + 25, y + 128, "A moment for what matters.", 12, "#6E7A66")
    rect(x + 22, y + 157, 274, 103, "#E9EDDF", 22)
    rect(x + 22, y + 275, 130, 70, "#B4C4B2", 18)
    rect(x + 165, y + 275, 131, 70, "#C5B6CD", 18)
    rect(x, y + 44, 318, 588, "#29242E", 0)
    # Overlay scrim at lower opacity by replacing preceding rectangle.
    out[-1] = out[-1].replace('fill="#29242E"', 'fill="#29242E" fill-opacity=".24"')
    sh = 314 if i == 0 else (336 if i == 1 else 390)
    sy = y + 643 - sh
    rect(x + 10, sy, 298, sh, "#F8F6F1", 27)
    rect(x + 137, sy + 10, 44, 3, "#D6CCD9", 2)
    spark(x + 37, sy + 39, 9)
    text(x + 53, sy + 46, "lfm", 21, weight=600)
    text(x + 231, sy + 45, "↗", 20, "#7C6B87")
    text(x + 271, sy + 45, "×", 24, "#7C6B87")
    if i == 0:
        text(x + 29, sy + 98, "A little help,", 27, font="serif")
        text(x + 29, sy + 133, "right when you need it.", 25, font="serif")
        text(x + 29, sy + 164, "A question, an idea, or a fresh start.", 12, "#807587")
        pill(x + 27, sy + 186, 117, "Spark an idea ↗")
        pill(x + 151, sy + 186, 140, "Explain simply ↗")
        composer(x + 21, sy + 236, 276)
    elif i == 1:
        text(x + 29, sy + 91, "I’m listening.", 28, font="serif")
        text(x + 29, sy + 119, "Go on. What’s on your mind?", 12, "#807587")
        for k, h in enumerate([12, 24, 43, 25, 59, 34, 47, 21, 12]):
            rect(x + 89 + k * 15, sy + 180 - h / 2, 5, h, "#9574BD", 3)
        pill(x + 99, sy + 229, 119, "Done speaking", "#29242E", "#FFFFFF")
        text(x + 81, sy + 298, "Your mic is on · Cancel", 11, "#807587")
    else:
        text(x + 29, sy + 84, "A small way to reset my day?", 12, "#807587")
        text(x + 29, sy + 123, "Try a five-minute reset.", 21, font="serif")
        for j, line in enumerate(
            [
                "Step away from your screen, get",
                "some water, and choose just one",
                "small thing to do next.",
            ]
        ):
            text(x + 29, sy + 158 + j * 22, line, 13, "#645C68")
        pill(x + 27, sy + 230, 69, "Copy", "#ECE8E2", "#645C68")
        pill(x + 103, sy + 230, 112, "Read aloud", "#ECE8E2", "#645C68")
        composer(x + 21, sy + 302, 276, "Ask a follow-up…")
# Bottom notes and widget
text(48, 913, "ONE FAMILY, DIFFERENT MOMENTS", 12, "#776F7C", 600)
text(48, 946, "The app is a place to return to.", 19, font="serif")
text(48, 976, "The floating panel is a short, useful interruption.", 19, font="serif")
text(48, 1005, "The widget is the quickest way in.", 19, font="serif")
text(
    48,
    1050,
    "Concept only. Includes planned features such as Read aloud. No app changes applied in this planning pass.",
    12,
    "#776F7C",
)
rect(828, 903, 430, 166, "#F8F6F1", 25)
spark(854, 931, 9)
text(873, 939, "lfm", 21, weight=600)
pill(1159, 916, 77, "• Ready", "#E5EADD", "#526143")
text(850, 980, "A little help, right here.", 26, font="serif")
rect(848, 1000, 225, 49, "#29242E", 24)
text(908, 1030, "Ask anything", 14, "#FFFFFF")
rect(1084, 1000, 153, 49, "#EAE1F1", 24)
text(1143, 1030, "Speak", 14, "#64428D")
mic(1114, 1022, "#64428D")
text(1300, 936, "Calm, clear states", 18, weight=500)
text(1300, 968, "Ready → Listening → Thinking → Answering", 13, "#776F7C")
text(1300, 997, "Mic → Send → Stop; never competing actions.", 13, "#776F7C")
text(1300, 1026, "Compact first. Expand when you want more.", 13, "#776F7C")
e("</svg>")
Path(__file__).with_name("lfm-design-concept.svg").write_text("\n".join(out))
