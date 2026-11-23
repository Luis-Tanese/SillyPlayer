import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Window
import SillyPlayer 1.0

FocusScope {
    id: pane
    required property var shell
    focus: shell.page === "Browse"
    property string shownProvider: "youtube"
    property var sourceViews: ({})
    Connections {
        target: online
        function onProviderChanged() {
            pane.sourceViews[pane.shownProvider] = {query: query.text, scroll: results.contentY};
            pane.shownProvider = online.activeProvider;
            const state = pane.sourceViews[pane.shownProvider] || {query: "", scroll: 0};
            query.text = state.query;
            shell.closeVideo();
            Qt.callLater(function() { results.contentY = Math.max(0, Math.min(state.scroll, results.contentHeight - results.height)); });
        }
    }
    function focusSearch() {
        query.forceActiveFocus();
        query.selectAll();
    }
    function submitSearch() {
        if (!query.text.trim())
            return;
        shell.closeVideo();
        online.search(query.text);
    }
    ColumnLayout {
        anchors.fill: parent
        anchors.margins: pane.width > 700 ? 32 : 20
        spacing: 18
        RowLayout {
            Layout.fillWidth: true
            spacing: 6
            Repeater {
                model: [{id: "youtube", name: "YouTube"}, {id: "soundcloud", name: "SoundCloud"}, {id: "direct", name: "Direct Link"}]
                FlatButton {
                    required property var modelData
                    objectName: "provider_" + modelData.id
                    text: modelData.name
                    navigation: true
                    checked: online.activeProvider === modelData.id
                    onClicked: online.setActiveProvider(modelData.id)
                }
            }
            Item { Layout.fillWidth: true }
        }
        RowLayout {
            Layout.fillWidth: true
            spacing: 10
            FlatButton {
                glyph: "arrow-left"
                hint: "Back to results"
                visible: shell.videoOpen
                onClicked: shell.closeVideo()
            }
            AppField {
                id: query
                objectName: "youtubeSearch"
                Layout.fillWidth: true
                search: true
                placeholderText: online.activeProvider === "direct" ? "Paste an audio file URL" : "Search " + online.providerName + " or paste a URL"
                onAccepted: pane.submitSearch()
            }
            FlatButton {
                objectName: "downloadYouTubePlaylist"
                visible: online.activeProvider === "youtube"
                text: pane.width > 650 ? "Playlist / mix" : ""
                glyph: "list-music"
                hint: "Choose songs from a YouTube playlist or mix to download"
                onClicked: online.playlist.show()
            }
        }
        RowLayout {
            visible: online.busy
            spacing: 10
            AppBusy {
                running: parent.visible
                implicitWidth: 24
                implicitHeight: 24
            }
            Label {
                text: "Searching " + online.providerName + "…"
                color: Theme.muted
                font.pixelSize: 12
            }
        }
        ColumnLayout {
            visible: online.activeProvider !== "direct" && !shell.videoOpen && !online.results.length && !online.busy
            Layout.fillWidth: true
            spacing: 6
            Label {
                text: online.history.length ? "Recent searches" : "Search for a song or an artist."
                color: Theme.muted
                Layout.topMargin: 12
                Layout.bottomMargin: 8
                font.pixelSize: 13
            }
            Repeater {
                model: online.history
                RowLayout {
                    required property var modelData
                    Layout.fillWidth: true
                    FlatButton {
                        text: modelData.query
                        Layout.fillWidth: true
                        onClicked: {
                            query.text = modelData.query;
                            pane.submitSearch();
                        }
                    }
                    FlatButton {
                        glyph: "x"
                        hint: "Remove search"
                        onClicked: online.removeHistory(modelData.query)
                    }
                }
            }
            FlatButton {
                text: "Clear history"
                visible: online.history.length > 0
                onClicked: online.clearHistory()
            }
        }
        Label {
            visible: online.activeProvider === "direct"
            text: "Paste a link to an audio file and press Enter to download.\nOriginal files only. Web pages, live radio and playlists are unsupported."
            Layout.fillWidth: true
            wrapMode: Text.WordWrap
            color: Theme.muted
            font.pixelSize: 13
            lineHeight: 1.4
        }
        Item { visible: online.activeProvider === "direct"; Layout.fillHeight: true }
        ListView {
            id: results
            objectName: "youtubeResults"
            visible: !shell.videoOpen && online.activeProvider !== "direct"
            Layout.fillWidth: true
            Layout.fillHeight: true
            model: StableListModel {
                rows: online.results
            }
            clip: true
            spacing: 6
            ScrollBar.vertical: ScrollBar {
            }
            delegate: Rectangle {
                id: result
                objectName: "youtubeResult"
                required property var record
                property bool lookingUp: online.downloadResolving && online.downloadLookupUrl === record.webpage_url
                width: results.width
                height: pane.width > 650 ? 116 : 96
                color: hover.hovered ? Qt.alpha(Theme.hover, 0.42) : "transparent"
                radius: 10
                HoverHandler {
                    id: hover
                }
                MouseArea {
                    anchors.fill: parent
                    cursorShape: Qt.PointingHandCursor
                    onClicked: online.resolve(result.record.webpage_url, true)
                }
                RowLayout {
                    anchors.fill: parent
                    anchors.margins: 8
                    spacing: 18
                    Rectangle {
                        Layout.preferredWidth: pane.width > 650 ? 176 : 120
                        Layout.preferredHeight: width * 9 / 16
                        color: Theme.surface
                        radius: 8
                        clip: true
                        RemoteThumbnail {
                            id: thumbnail
                            objectName: "resultThumbnail"
                            anchors.fill: parent
                            candidates: result.record.thumbnailCandidates || (result.record.thumbnail ? [result.record.thumbnail] : [])
                        }
                        AppIcon {
                            anchors.centerIn: parent
                            visible: thumbnail.status !== Image.Ready
                            glyph: "play"
                            width: 24
                            height: 24
                            opacity: 0.6
                        }
                        Rectangle {
                            anchors.right: parent.right
                            anchors.bottom: parent.bottom
                            anchors.margins: 6
                            visible: !!result.record.duration
                            width: durationLabel.implicitWidth + 10
                            height: 21
                            radius: 4
                            color: "#c0121218"
                            Label {
                                id: durationLabel
                                anchors.centerIn: parent
                                text: app.time(result.record.duration || 0)
                                color: "#f0f0f5"
                                font.pixelSize: 10
                            }
                        }
                    }
                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 7
                        Label {
                            text: result.record.title || "Untitled track"
                            color: Theme.text
                            font.pixelSize: pane.width > 650 ? 15 : 13
                            font.weight: Font.Medium
                            maximumLineCount: 2
                            wrapMode: Text.WordWrap
                            elide: Text.ElideRight
                            Layout.fillWidth: true
                        }
                        Label {
                            text: result.record.channel || result.record.uploader || "Unknown creator"
                            color: Theme.muted
                            font.pixelSize: 12
                            elide: Text.ElideRight
                            Layout.fillWidth: true
                        }
                    }
                    FlatButton {
                        objectName: "downloadResult"
                        glyph: result.lookingUp ? "" : "download"
                        text: result.lookingUp ? "Loading…" : pane.width > 750 ? "Download" : ""
                        hint: result.lookingUp ? "Getting audio" : "Download audio"
                        enabled: !result.lookingUp
                        onClicked: shell.download(result.record)
                    }
                }
            }
            footer: FlatButton {
                text: "Load more results"
                visible: results.count > 0
                enabled: !online.busy
                onClicked: online.search(query.text, true)
            }
        }
        ColumnLayout {
            visible: shell.videoOpen
            Layout.fillWidth: true
            Layout.fillHeight: true
            spacing: 0
            Item {
                Layout.fillWidth: true
                Layout.fillHeight: true
                Layout.minimumHeight: 110
                Art {
                    id: youtubeArtwork
                    objectName: "youtubeArtwork"
                    anchors.centerIn: parent
                    width: Math.max(80, Math.min(parent.width, parent.height, 440))
                    height: width
                    source: online.details.artwork || ""
                }
                AppBusy { anchors.centerIn: parent; running: online.resolving || youtubePlayer.buffering; visible: running; implicitWidth: 32; implicitHeight: 32 }
            }
            Label {
                Layout.fillWidth: true
                text: online.lookupError || youtubePlayer.error
                visible: !!text
                color: Theme.danger
                wrapMode: Text.WordWrap
                maximumLineCount: 3
                elide: Text.ElideRight
            }
            FlatButton { text: "Retry audio"; glyph: "rotate-ccw"; visible: !online.resolving && !!(online.lookupError || youtubePlayer.error); onClicked: online.retryVideo() }
            SeekBar {
                id: youtubeSeek
                objectName: "videoSeek"
                Layout.fillWidth: true
                backend: youtubePlayer
            }
            RowLayout {
                Layout.fillWidth: true
                spacing: 8
                FlatButton {
                    objectName: "videoToggle"
                    glyph: (youtubePlayer.paused || youtubePlayer.pausing) ? "play" : "pause"
                    hint: "Play / Pause " + online.providerName + " audio"
                    implicitWidth: 32
                    leftPadding: 7
                    rightPadding: 7
                    enabled: youtubePlayer.loaded && !online.resolving
                    onClicked: youtubePlayer.toggle()
                }
                Label {
                    text: app.time(youtubeSeek.displayedPosition) + " / " + (youtubePlayer.duration > 0 ? app.time(youtubePlayer.duration) : "–:––")
                    color: Theme.muted
                    font.pixelSize: 11
                }
                Item {
                    Layout.fillWidth: true
                }
                FlatButton {
                    glyph: youtubePlayer.volume > 0 ? "volume-2" : "volume-x"
                    hint: "Mute " + online.providerName + " audio"
                    onClicked: youtubePlayer.volume = youtubePlayer.volume > 0 ? 0 : 70
                }
                VolumeSlider {
                    objectName: "videoVolume"
                    Layout.preferredWidth: pane.width > 650 ? 112 : 80
                    from: 0
                    to: 100
                    backend: youtubePlayer
                    Accessible.name: online.providerName + " volume"
                }

            }
            RowLayout {
                Layout.fillWidth: true
                Layout.leftMargin: 7
                Layout.topMargin: 14
                spacing: 16
                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 5
                    Label {
                        objectName: "videoTitle"
                        text: online.details.track || online.details.title || (online.resolving ? "Loading audio…" : "")
                        color: Theme.text
                        font.pixelSize: pane.width > 650 ? 19 : 16
                        font.weight: Font.DemiBold
                        maximumLineCount: 2
                        wrapMode: Text.WordWrap
                        elide: Text.ElideRight
                        Layout.fillWidth: true
                    }
                    Label {
                        text: (online.details.artist || online.details.creator || "Unknown artist") + ((online.details.uploader || online.details.channel) ? " · Uploader: " + (online.details.uploader || online.details.channel) : "")
                        color: Theme.muted
                        font.pixelSize: 12
                        elide: Text.ElideRight
                        Layout.fillWidth: true
                    }
                }
                FlatButton {
                    objectName: "downloadVideo"
                    glyph: online.downloadResolving ? "" : "download"
                    text: online.downloadResolving ? "Loading…" : pane.width > 500 ? "Download" : ""
                    enabled: !!shell.videoDetails.id && !online.resolving && !online.downloadResolving
                    onClicked: shell.download(online.details)
                }
            }
            Item {
                Layout.fillHeight: true
                Layout.minimumHeight: 0
                Layout.maximumHeight: 24
            }
        }
        Label {
            Layout.fillWidth: true
            text: online.status
            visible: !!text && !shell.videoOpen && !online.busy
            wrapMode: Text.WordWrap
            color: Theme.muted
            font.pixelSize: 12
        }
    }
}
