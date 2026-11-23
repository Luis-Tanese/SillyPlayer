import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

FocusScope {
    id: bar
    required property var shell
    implicitHeight: 88
    Rectangle {
        anchors.fill: parent
        color: Theme.base
        Rectangle {
            width: parent.width
            height: 1
            color: Theme.separator
        }
    }
    SeekBar {
        anchors.top: parent.top
        anchors.topMargin: -8
        width: parent.width
        backend: music
        thin: true
        z: 2
    }
    RowLayout {
        anchors.fill: parent
        anchors.topMargin: 15
        anchors.bottomMargin: 13
        anchors.leftMargin: 28
        anchors.rightMargin: 28
        spacing: bar.width > 800 ? 24 : 10
        RowLayout {
            Layout.fillWidth: true
            Layout.preferredWidth: bar.width * 0.30
            Layout.minimumWidth: 155
            spacing: 13
            Art {
                source: app.current.art || ""
                implicitWidth: 48
                implicitHeight: 48
                interactive: !!app.current.id
                onClicked: shell.expand()
            }
            ColumnLayout {
                Layout.fillWidth: true
                Layout.minimumWidth: 70
                spacing: 2
                RowLayout {
                    Layout.fillWidth: true
                    spacing: 6
                    Label {
                        Layout.fillWidth: true
                        text: app.current.title || "Nothing playing"
                        color: Theme.text
                        elide: Text.ElideRight
                        font.pixelSize: 13
                        font.weight: Font.Medium
                    }
                    Label {
                        objectName: "compactSpeedBadge"
                        text: app.modifierLabel(app.current)
                        visible: !!text
                        Layout.maximumWidth: 100
                        elide: Text.ElideRight
                        color: Theme.accent; font.pixelSize: 10
                        ToolTip.visible: speedHover.hovered
                        ToolTip.text: text
                        HoverHandler { id: speedHover }
                    }
                }
                Label {
                    Layout.fillWidth: true
                    text: music.error ? "Playback error: " + music.error : youtubePlayer.loaded && !youtubePlayer.paused ? "Music paused · " + online.providerName + " playing" : (app.current.artist || "Choose something from your library")
                    color: music.error ? Theme.danger : Theme.muted
                    elide: Text.ElideRight
                    font.pixelSize: 11
                }
                Label {
                    objectName: "playlistContext"
                    Layout.fillWidth: true
                    visible: app.sourceContext.kind === "playlist"
                    text: "From Playlist: " + (app.sourceContext.name || "")
                    color: Theme.muted; font.pixelSize: 10; elide: Text.ElideRight
                    ToolTip.visible: contextHover.hovered
                    ToolTip.text: text
                    HoverHandler { id: contextHover }
                }
            }
        }
        RowLayout {
            spacing: 3
            FlatButton {
                glyph: app.mode === 2 ? "shuffle" : app.mode === 1 ? "repeat-1" : "repeat"
                hint: ["Repeat queue", "Repeat one", "Shuffle"][app.mode]
                onClicked: app.cycleMode()
            }
            FlatButton {
                enabled: !!app.current.id
                glyph: "skip-back"
                hint: "Previous"
                onClicked: app.previous()
            }
            FlatButton {
                enabled: !!app.current.id
                glyph: (music.paused || music.pausing) ? "play" : "pause"
                hint: "Play / Pause music"
                primary: true
                implicitWidth: 42
                implicitHeight: 42
                onClicked: app.toggle()
            }
            FlatButton {
                enabled: !!app.current.id
                glyph: "skip-forward"
                hint: "Next"
                onClicked: app.next()
            }
            FlatButton {
                enabled: !!app.current.id
                glyph: "heart"
                hint: app.currentLiked ? "Remove from Liked Songs" : "Add to Liked Songs"
                checked: app.currentLiked
                onClicked: app.like(app.current.id)
            }
            FlatButton {
                glyph: "list-music"
                hint: "Playback queue"
                onClicked: shell.toggleQueue()
            }
        }
        RowLayout {
            Layout.fillWidth: true
            Layout.preferredWidth: bar.width * 0.30
            Layout.minimumWidth: bar.width > 780 ? 144 : 36
            spacing: 5
            Item {
                Layout.fillWidth: true
            }
            Label {
                visible: bar.width > 1020
                text: app.time(music.position) + " / " + (music.duration > 0 ? app.time(music.duration) : "–:––")
                color: Theme.muted
                font.pixelSize: 10
                Layout.rightMargin: 8
            }
            FlatButton {
                visible: app.sleepTimer.active
                text: app.time(app.sleepTimer.remainingSeconds)
                glyph: "clock"
                hint: "Sleep timer"
                onClicked: shell.showSleepTimer()
            }
            FlatButton {
                glyph: music.volume > 0 ? "volume-2" : "volume-x"
                hint: "Mute music"
                onClicked: music.volume = music.volume > 0 ? 0 : 70
            }
            VolumeSlider {
                objectName: "musicVolume"
                visible: bar.width > 780
                Layout.preferredWidth: 98
                from: 0
                to: 100
                backend: music
                Accessible.name: "Music volume"
            }
        }
    }
}
