"""Stand-in for audioop-lts on Android.

discord.py imports audioop at start-up but only uses it for voice playback,
which Python Runner does not support. Any real use raises a clear error.
"""


class error(Exception):
    pass


def __getattr__(name):
    def unsupported(*args, **kwargs):
        raise NotImplementedError(
            f"audioop.{name} は使えません（Python Runner は音声機能に対応していません）"
        )

    if name.startswith("__"):
        raise AttributeError(name)
    return unsupported
