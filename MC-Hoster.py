"""MC Hoster: host a Minecraft server (Java or Bedrock) from your PC, with Gh0st AC. By DarkerDawnFR.

    MC-Hoster.exe                          the setup window
    MC-Hoster.exe --start "<folder>"       open and start a saved server (used by the scheduler)
    MC-Hoster.exe --tour <folder>          saves screenshots of every tab (used by CI)
    python MC-Hoster.py --selftest <type> <version> [--no-gh0st] [--addons]
                                           headless: set up a real server, start it, check it, stop it
    python MC-Hoster.py --addons-test <type> <version>
                                           checks every Plugins & Mods preset can be downloaded
    python MC-Hoster.py --schedule-test    creates, checks and deletes a Windows scheduled task
    python MC-Hoster.py --orphan-test      a server can't outlive MC Hoster, and old ones are found and stopped
"""
import sys


def main():
    args = sys.argv[1:]
    if args[:1] == ["--selftest"]:
        from mchoster.selftest import run
        sys.exit(run(args[1:]))
    if args[:1] == ["--addons-test"]:
        from mchoster.selftest import addons_test
        sys.exit(addons_test(args[1:]))
    if args[:1] == ["--respack-test"]:
        from mchoster.selftest import respack_test
        sys.exit(respack_test())
    if args[:1] == ["--schedule-test"]:
        from mchoster.selftest import schedule_test
        sys.exit(schedule_test())
    if args[:1] == ["--orphan-test"]:
        from mchoster.selftest import orphan_test
        sys.exit(orphan_test())
    if args[:1] == ["--hold-server"]:  # used by --orphan-test: start a server, then die without stopping it
        from mchoster.selftest import hold_server
        sys.exit(hold_server(args[1]))
    if args[:1] == ["--tour"]:
        from mchoster.tour import run
        sys.exit(run(args[1] if len(args) > 1 else "screenshots"))
    from mchoster.app import main as gui
    gui(args[1] if args[:1] == ["--start"] and len(args) > 1 else None)


if __name__ == "__main__":
    main()
