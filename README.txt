FakePing 1.0.0 - needs Paper/Purpur 26.3 + PlaceholderAPI.

Build: push this folder to a GitHub repo -> Actions tab -> "Build FakePing" -> download the artifact.
       (or with JDK 25 + Gradle 9.1+: gradle build)

Setup: put FakePing.jar in plugins/, restart, edit plugins/FakePing/config.yml.
In TAB's config use %ping_fake%. If it doesn't update fast enough, add it to TAB's
placeholder-refresh-intervals (e.g. "%ping_fake%": 500).
Commands: /fakeping reload | status | set <player> <min> <max> | clear <player>
