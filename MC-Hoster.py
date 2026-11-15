"""MC Hoster: host a Minecraft server from your PC, with Gh0st AC. By DarkerDawnFR.

    MC-Hoster.exe                          the setup window
    MC-Hoster.exe --tour <folder>          saves screenshots of every tab (used by CI)
    python MC-Hoster.py --selftest <type> <version> [--no-gh0st]
                                           headless: set up a real server, start it, check it, stop it
"""
import sys


def main():
    args = sys.argv[1:]
    if args[:1] == ["--selftest"]:
        from mchoster.selftest import run
        sys.exit(run(args[1:]))
    if args[:1] == ["--tour"]:
        from mchoster.tour import run
        sys.exit(run(args[1] if len(args) > 1 else "screenshots"))
    from mchoster.app import main as gui
    gui()


if __name__ == "__main__":
    main()
