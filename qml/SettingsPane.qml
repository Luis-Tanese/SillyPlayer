import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Item {
    id: pane
    objectName: "settingsPane"
    required property var shell
    property string category: "Appearance"
    property bool modalActive: false
    readonly property var categories: ["Appearance", "Playback", "Library and storage", "Phone sync", "Backup and restore", "Keyboard shortcuts", "About"]
    readonly property var glyphs: ["settings", "music-2", "folder", "rotate-ccw", "download", "list-checks", "info"]
    readonly property bool wide: width >= 820
    RowLayout {
        anchors.fill: parent
        anchors.margins: pane.wide ? 24 : 16
        spacing: 24
        ScrollView {
            Layout.preferredWidth: pane.wide ? 228 : pane.width
            Layout.fillHeight: true
            visible: pane.wide || !pane.category
            contentWidth: availableWidth
            clip: true
            ColumnLayout {
                width: parent.width
                spacing: 4
                Label {
                    text: "Settings"
                    color: Theme.text
                    font.pixelSize: 22
                    font.weight: Font.DemiBold
                    Layout.leftMargin: 12
                    Layout.bottomMargin: 12
                }
                Repeater {
                    model: pane.categories
                    FlatButton {
                        required property string modelData
                        required property int index
                        objectName: "settingsCategory" + index
                        contentItem: RowLayout {
                            spacing: 10
                            AppIcon { glyph: pane.glyphs[index]; color: pane.category === modelData ? Theme.accent : Theme.muted; Layout.preferredWidth: 18; Layout.preferredHeight: 18 }
                            Label { text: modelData; color: pane.category === modelData ? Theme.accent : Theme.text; font.pixelSize: 13; font.weight: pane.category === modelData ? Font.Medium : Font.Normal; Layout.fillWidth: true; elide: Text.ElideRight }
                        }
                        text: modelData
                        glyph: pane.glyphs[index]
                        checked: pane.category === modelData
                        Layout.fillWidth: true
                        implicitHeight: 40
                        leftPadding: 12
                        rightPadding: 12
                        onClicked: { pane.category = modelData; settingsScroll.contentItem.contentY = 0; }
                    }
                }
            }
        }
        Rectangle {
            visible: pane.wide
            Layout.preferredWidth: 1
            Layout.fillHeight: true
            color: Theme.separator
        }
        ColumnLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            visible: !!pane.category
            spacing: 12
            FlatButton {
                text: "Settings"
                glyph: "arrow-left"
                outlined: true
                visible: !pane.wide
                onClicked: pane.category = ""
            }
            ScrollView {
                id: settingsScroll
                objectName: "settingsScroll"
                Layout.fillWidth: true
                Layout.fillHeight: true
                contentWidth: availableWidth
                clip: true
                Item {
                    width: settingsScroll.availableWidth
                    implicitHeight: sectionLoader.implicitHeight + 24
                    Loader {
                        id: sectionLoader
                        width: Math.min(parent.width - 8, 720)
                        sourceComponent: [category0, category2, category3, category4, category5, category6, category7][pane.categories.indexOf(pane.category)] || null
                    }
                }
            }
        }
    }
    Component {
        id: category0
        ColumnLayout {
            spacing: 16
            Label {
                text: "Appearance"
                color: Theme.text
                font.pixelSize: 20
                font.weight: Font.DemiBold
            }
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: appearanceCard.implicitHeight + 36
                radius: 10
                color: Theme.surface
                border.width: 1
                border.color: Theme.separator
                ColumnLayout {
                    id: appearanceCard
                    anchors.fill: parent
                    anchors.margins: 18
                    spacing: 14
                    Label {
                        text: "Theme"
                        color: Theme.text
                        font.pixelSize: 15
                        font.weight: Font.Medium
                    }
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 12
                        Label {
                            text: "Color palette"
                            color: Theme.muted
                            Layout.preferredWidth: 120
                        }
                        AppCombo {
                            model: Object.keys(Theme.palettes)
                            currentIndex: model.indexOf(Theme.canonicalPalette(app.settings.palette))
                            Layout.preferredWidth: 180
                            onActivated: app.setSetting("palette", currentText)
                        }
                    }
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 12
                        Label {
                            text: "Mode"
                            color: Theme.muted
                            Layout.preferredWidth: 120
                        }
                        AppCombo {
                            model: ["Dark", "Light"]
                            currentIndex: app.settings.light ? 1 : 0
                            Layout.preferredWidth: 180
                            onActivated: app.setSetting("light", currentIndex === 1)
                        }
                    }
                }
            }
        }
    }
    Component {
        id: category2
        ColumnLayout {
            spacing: 16
            Label {
                text: "Playback"
                color: Theme.text
                font.pixelSize: 20
                font.weight: Font.DemiBold
            }
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: audioCard.implicitHeight + 36
                radius: 10
                color: Theme.surface
                border.width: 1
                border.color: Theme.separator
                ColumnLayout {
                    id: audioCard
                    anchors.fill: parent
                    anchors.margins: 18
                    spacing: 14
                    Label {
                        text: "Audio & transitions"
                        color: Theme.text
                        font.pixelSize: 15
                        font.weight: Font.Medium
                    }
                    Flow {
                        Layout.fillWidth: true
                        spacing: 10
                        FlatButton {
                            text: "Equalizer"
                            glyph: "settings"
                            outlined: true
                            checked: !!app.settings.eqEnabled
                            onClicked: pane.shell.openEqualizer()
                        }
                        FlatButton {
                            text: app.sleepTimer.active ? "Sleep timer · " + app.time(app.sleepTimer.remainingSeconds) : "Sleep timer"
                            glyph: "clock"
                            outlined: true
                            checked: app.sleepTimer.active
                            onClicked: pane.shell.showSleepTimer()
                        }
                    }
                    Rectangle { Layout.fillWidth: true; implicitHeight: 1; color: Theme.separator }
                    AppCheck {
                        objectName: "smartShuffleEnabled"
                        text: "Smart shuffle"
                        checked: !!app.settings.smartShuffle
                        onToggled: app.setSetting("smartShuffle", checked)
                    }
                    Label {
                        text: "Avoids playing the same artist back to back and favors songs you haven't heard recently."
                        color: Theme.muted
                        font.pixelSize: 12
                        Layout.fillWidth: true
                        wrapMode: Text.WordWrap
                    }
                    Rectangle { Layout.fillWidth: true; implicitHeight: 1; color: Theme.separator }
                    AppCheck {
                        objectName: "pauseFadeEnabled"
                        text: "Fade out on pause"
                        checked: app.settings.pauseFadeEnabled
                        onToggled: app.setSetting("pauseFadeEnabled", checked)
                    }
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 12
                        enabled: app.settings.pauseFadeEnabled
                        opacity: enabled ? 1 : 0.45
                        Label { text: "Fade duration"; color: Theme.muted; Layout.preferredWidth: 110 }
                        AppSlider {
                            objectName: "pauseFadeDuration"
                            from: 0.05; to: 5; stepSize: 0.05; decimals: 2; suffix: " s"
                            value: app.settings.pauseFadeMs / 1000
                            Layout.fillWidth: true; Layout.maximumWidth: 260
                            onMoved: if (!pressed) app.setSetting("pauseFadeMs", Math.round(value * 1000))
                            onPressedChanged: if (!pressed) app.setSetting("pauseFadeMs", Math.round(value * 1000))
                            Accessible.name: "Pause fade duration"
                        }
                        AppField {
                            objectName: "pauseFadeSeconds"
                            Layout.preferredWidth: 80
                            text: (app.settings.pauseFadeMs / 1000).toFixed(2)
                            validator: DoubleValidator { bottom: 0.05; top: 5; decimals: 2; locale: "C" }
                            onEditingFinished: {
                                const value = Number(text);
                                if (isFinite(value) && value >= 0.05 && value <= 5) app.setSetting("pauseFadeMs", Math.round(value * 1000));
                                else text = (app.settings.pauseFadeMs / 1000).toFixed(2);
                            }
                            Accessible.name: "Fade duration in seconds"
                        }
                        Label { text: "s"; color: Theme.muted }
                    }
                    Rectangle { Layout.fillWidth: true; implicitHeight: 1; color: Theme.separator }
                    AppCheck {
                        objectName: "crossfadeEnabled"
                        text: "Smooth transition fade between songs"
                        checked: (app.settings.crossfadeMs || 0) > 0
                        onToggled: app.setSetting("crossfadeMs", checked ? 3000 : 0)
                    }
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 12
                        enabled: (app.settings.crossfadeMs || 0) > 0
                        opacity: enabled ? 1 : 0.45
                        Label { text: "Transition fade"; color: Theme.muted; Layout.preferredWidth: 110 }
                        AppSlider {
                            objectName: "crossfadeDuration"
                            from: 1.0; to: 8.0; stepSize: 0.5; decimals: 1; suffix: " s"
                            value: Math.max(1.0, (app.settings.crossfadeMs || 3000) / 1000)
                            Layout.fillWidth: true; Layout.maximumWidth: 260
                            onMoved: if (!pressed) app.setSetting("crossfadeMs", Math.round(value * 1000))
                            onPressedChanged: if (!pressed) app.setSetting("crossfadeMs", Math.round(value * 1000))
                            Accessible.name: "Song transition fade duration"
                        }
                        AppField {
                            objectName: "crossfadeSeconds"
                            Layout.preferredWidth: 80
                            text: (Math.max(1000, app.settings.crossfadeMs || 3000) / 1000).toFixed(1)
                            validator: DoubleValidator { bottom: 1.0; top: 8.0; decimals: 1; locale: "C" }
                            onEditingFinished: {
                                const value = Number(text);
                                if (isFinite(value) && value >= 1.0 && value <= 8.0) app.setSetting("crossfadeMs", Math.round(value * 1000));
                                else text = (Math.max(1000, app.settings.crossfadeMs || 3000) / 1000).toFixed(1);
                            }
                            Accessible.name: "Song transition fade in seconds"
                        }
                        Label { text: "s"; color: Theme.muted }
                    }
                }
            }
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: discordCard.implicitHeight + 36
                radius: 10
                color: Theme.surface
                border.width: 1
                border.color: Theme.separator
                ColumnLayout {
                    id: discordCard
                    anchors.fill: parent
                    anchors.margins: 18
                    spacing: 12
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 10
                        Label {
                            text: "Discord Rich Presence"
                            color: Theme.text
                            font.pixelSize: 15
                            font.weight: Font.Medium
                            Layout.fillWidth: true
                        }
                        Label {
                            text: app.discord.status
                            color: app.discord.connected ? Theme.accent : Theme.muted
                            font.pixelSize: 12
                        }
                    }
                    AppCheck {
                        objectName: "discordRpcEnabled"
                        text: "Show currently playing song on Discord"
                        checked: !!app.settings.discordRpcEnabled
                        onToggled: app.setSetting("discordRpcEnabled", checked)
                    }
                    AppCheck {
                        objectName: "discordRpcLookupCovers"
                        text: "Look up cover art online for local songs"
                        enabled: !!app.settings.discordRpcEnabled
                        checked: app.settings.discordRpcLookupCovers !== false
                        onToggled: app.setSetting("discordRpcLookupCovers", checked)
                    }
                    AppCheck {
                        objectName: "discordRpcUploadCovers"
                        text: "Upload embedded cover for unmatched local songs"
                        enabled: !!app.settings.discordRpcEnabled
                        checked: !!app.settings.discordRpcUploadCovers
                        onToggled: app.setSetting("discordRpcUploadCovers", checked)
                    }
                    Flow {
                        Layout.fillWidth: true
                        spacing: 10
                        FlatButton {
                            text: "Refresh cover cache"
                            glyph: "rotate-ccw"
                            outlined: true
                            enabled: !!app.settings.discordRpcEnabled
                            onClicked: app.discord.clearCoverCache()
                        }
                    }
                    Label {
                        text: "Uses YouTube thumbnails when available, otherwise looks up public covers via iTunes/Deezer and caches them in the database. Optional upload sends resized embedded art to a temporary 72h host only when lookup finds no match."
                        color: Theme.muted
                        font.pixelSize: 12
                        Layout.fillWidth: true
                        wrapMode: Text.WordWrap
                    }
                }
            }
        }
    }
    Component {
        id: category3
        ColumnLayout {
            id: storageSection
            spacing: 16
            property string pendingClearFolder: ""
            property string clearStatus: ""
            property string repairStatus: ""
            Component.onDestruction: pane.modalActive = false
            Label {
                text: "Library and storage"
                color: Theme.text
                font.pixelSize: 20
                font.weight: Font.DemiBold
            }
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: foldersCard.implicitHeight + 36
                radius: 10
                color: Theme.surface
                border.width: 1
                border.color: Theme.separator
                ColumnLayout {
                    id: foldersCard
                    anchors.fill: parent
                    anchors.margins: 18
                    spacing: 12
                    Label {
                        text: "Watched music folders"
                        color: Theme.text
                        font.pixelSize: 15
                        font.weight: Font.Medium
                    }
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 8
                        visible: library.scanning || !!library.status
                        AppBusy { running: library.scanning; visible: running; implicitWidth: 18; implicitHeight: 18 }
                        Label {
                            objectName: "libraryIndexStatus"
                            text: library.status
                            color: Theme.muted
                            font.pixelSize: 12
                            wrapMode: Text.WrapAnywhere
                            Layout.fillWidth: true
                        }
                    }
                    Repeater {
                        model: library.folders
                        Rectangle {
                            required property var modelData
                            Layout.fillWidth: true
                            implicitHeight: folderRow.implicitHeight + 12
                            radius: 8
                            color: Qt.alpha(Theme.hover, 0.35)
                            RowLayout {
                                id: folderRow
                                anchors.fill: parent
                                anchors.leftMargin: 12
                                anchors.rightMargin: 6
                                anchors.topMargin: 6
                                anchors.bottomMargin: 6
                                spacing: 4
                                Label {
                                    text: modelData.path + (modelData.available ? "" : " · Unavailable")
                                    color: Theme.text
                                    Layout.fillWidth: true
                                    elide: Text.ElideMiddle
                                }
                                FlatButton {
                                    glyph: "folder"
                                    hint: "Locate moved music folder"
                                    enabled: !app.libraryTools.busy
                                    onClicked: app.libraryTools.chooseFolder(modelData.id)
                                }
                                FlatButton {
                                    glyph: "trash-2"
                                    hint: "Move all songs in this folder to Trash"
                                    enabled: modelData.available && !library.scanning && !library.trashing && !app.sync.busy && !app.backup.busy
                                    onClicked: { storageSection.pendingClearFolder = modelData.path; clearFolderDialog.open(); }
                                }
                                FlatButton {
                                    glyph: "x"
                                    hint: "Remove watched folder (keep files)"
                                    onClicked: library.removeFolder(modelData.id)
                                }
                            }
                        }
                    }
                    Label {
                        visible: library.folders.length === 0
                        text: "No music folders added yet."
                        color: Theme.muted
                        font.pixelSize: 12
                    }
                    Flow {
                        Layout.fillWidth: true
                        spacing: 10
                        FlatButton {
                            text: "Add folder"
                            glyph: "folder"
                            outlined: true
                            onClicked: {
                                let path = app.chooseFolder();
                                if (path)
                                    library.addFolder(path);
                            }
                        }
                        FlatButton {
                            text: "Add files"
                            glyph: "plus"
                            outlined: true
                            onClicked: app.addFiles()
                        }
                        FlatButton {
                            text: "Rescan"
                            glyph: "rotate-ccw"
                            outlined: true
                            enabled: !library.scanning
                            onClicked: library.rescan()
                        }
                    }
                }
            }
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: downloadCard.implicitHeight + 36
                radius: 10
                color: Theme.surface
                border.width: 1
                border.color: Theme.separator
                ColumnLayout {
                    id: downloadCard
                    anchors.fill: parent
                    anchors.margins: 18
                    spacing: 12
                    Label {
                        text: "Download folder"
                        color: Theme.text
                        font.pixelSize: 15
                        font.weight: Font.Medium
                    }
                    Label {
                        text: app.settings.musicDir
                        color: Theme.muted
                        Layout.fillWidth: true
                        elide: Text.ElideMiddle
                        font.pixelSize: 12
                    }
                    Label {
                        text: "Audio files and a portable SQLite database copy (.sillyplayer-library-*.sqlite3) are kept in this folder so your playlists, likes, listening history and settings can be recovered."
                        color: Theme.muted
                        font.pixelSize: 12
                        wrapMode: Text.WordWrap
                        Layout.fillWidth: true
                    }
                    Label {
                        text: app.recovery.lastSaved > 0
                            ? ("Database last saved: " + Qt.formatDateTime(new Date(app.recovery.lastSaved), Qt.DefaultLocaleShortDate))
                            : "Database not saved to folder yet"
                        color: Theme.muted
                        font.pixelSize: 12
                        Layout.fillWidth: true
                    }
                    Label {
                        visible: !!app.recovery.status
                        text: app.recovery.status
                        color: Theme.muted
                        font.pixelSize: 12
                        wrapMode: Text.WordWrap
                        Layout.fillWidth: true
                    }
                    Flow {
                        Layout.fillWidth: true
                        spacing: 10
                        FlatButton {
                            text: "Change folder"
                            glyph: "folder"
                            outlined: true
                            onClicked: {
                                let path = app.chooseFolder(app.settings.musicDir);
                                if (path)
                                    app.setSetting("musicDir", path);
                            }
                        }
                        FlatButton {
                            text: app.recovery.saving ? "Saving database…" : "Save database now"
                            glyph: "download"
                            outlined: true
                            enabled: !!app.settings.musicDir && !app.recovery.saving && !library.scanning && !app.backup.busy
                            onClicked: app.recovery.saveNow(false)
                        }
                        FlatButton {
                            text: "Clear folder"
                            glyph: "trash-2"
                            outlined: true
                            danger: true
                            enabled: !!app.settings.musicDir && !library.scanning && !library.trashing && !app.sync.busy && !app.backup.busy
                            onClicked: { storageSection.pendingClearFolder = app.settings.musicDir; clearFolderDialog.open(); }
                        }
                    }
                    Label {
                        visible: !!storageSection.clearStatus
                        text: storageSection.clearStatus
                        color: Theme.muted
                        font.pixelSize: 12
                        wrapMode: Text.WordWrap
                        Layout.fillWidth: true
                    }
                }
            }
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: toolsCard.implicitHeight + 36
                radius: 10
                color: Theme.surface
                border.width: 1
                border.color: Theme.separator
                ColumnLayout {
                    id: toolsCard
                    anchors.fill: parent
                    anchors.margins: 18
                    spacing: 12
                    Label {
                        text: "Library tools"
                        color: Theme.text
                        font.pixelSize: 15
                        font.weight: Font.Medium
                    }
                    Flow {
                        Layout.fillWidth: true
                        spacing: 10
                        FlatButton { text: "Find missing files"; glyph: "folder"; outlined: true; enabled: !app.libraryTools.busy; onClicked: app.libraryTools.findMissing() }
                        FlatButton { text: "Find exact duplicates"; glyph: "list-checks"; outlined: true; enabled: !app.libraryTools.busy; onClicked: app.libraryTools.findDuplicates() }
                        FlatButton {
                            text: "Repair downloaded songs"
                            glyph: "rotate-ccw"
                            outlined: true
                            enabled: !library.scanning && !library.trashing && !app.sync.busy && !app.backup.busy
                            onClicked: {
                                const res = app.repairDownloadedSongs();
                                storageSection.repairStatus = res.message || "";
                            }
                        }
                        FlatButton {
                            objectName: "reinstateFolderBtn"
                            text: "Reinstate folder"
                            glyph: "folder"
                            outlined: true
                            enabled: !library.scanning && !library.trashing && !app.sync.busy && !app.backup.busy
                            onClicked: {
                                const path = app.chooseFolder(app.settings.musicDir);
                                if (path) {
                                    const res = app.reinstateFolder(path);
                                    storageSection.repairStatus = res.message || "";
                                }
                            }
                        }
                        FlatButton { text: "Open system Trash"; glyph: "trash-2"; outlined: true; onClicked: app.openTrash() }
                    }
                    Label {
                        visible: !!storageSection.repairStatus
                        text: storageSection.repairStatus
                        color: Theme.muted
                        font.pixelSize: 12
                        wrapMode: Text.WordWrap
                        Layout.fillWidth: true
                    }
                }
            }
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: playlistsCard.implicitHeight + 36
                radius: 10
                color: Theme.surface
                border.width: 1
                border.color: Theme.separator
                ColumnLayout {
                    id: playlistsCard
                    anchors.fill: parent
                    anchors.margins: 18
                    spacing: 10
                    Label {
                        text: "Playlists"
                        color: Theme.text
                        font.pixelSize: 15
                        font.weight: Font.Medium
                    }
                    AppCheck {
                        objectName: "allowPlaylistDuplicates"
                        text: "Allow duplicate songs in playlists"
                        checked: !!app.settings.allowPlaylistDuplicates
                        onToggled: app.setSetting("allowPlaylistDuplicates", checked)
                    }
                    Label {
                        text: "Off by default. When off, adding songs skips entries already in the playlist while keeping existing duplicates intact."
                        color: Theme.muted
                        font.pixelSize: 12
                        Layout.fillWidth: true
                        wrapMode: Text.WordWrap
                    }
                }
            }
            AppDialog {
                id: clearFolderDialog
                anchors.centerIn: Overlay.overlay
                width: Math.min(pane.width - 40, 460)
                title: "Clear music folder?"
                standardButtons: Dialog.Cancel
                onOpenedChanged: pane.modalActive = opened
                ColumnLayout {
                    width: parent.width
                    spacing: 12
                    Label {
                        text: storageSection.pendingClearFolder
                        color: Theme.text
                        wrapMode: Text.WrapAnywhere
                        Layout.fillWidth: true
                    }
                    Label {
                        text: "All audio files inside this folder will be moved to the system Trash and their songs removed from the library."
                        color: Theme.muted
                        wrapMode: Text.WordWrap
                        Layout.fillWidth: true
                    }
                    FlatButton {
                        text: "Move all songs to Trash"
                        glyph: "trash-2"
                        outlined: true
                        danger: true
                        Layout.alignment: Qt.AlignRight
                        onClicked: {
                            const res = app.clearMusicFolder(storageSection.pendingClearFolder);
                            storageSection.clearStatus = res.message || "";
                            clearFolderDialog.close();
                        }
                    }
                }
            }
        }
    }
    Component {
        id: category4
        ColumnLayout {
            spacing: 16
            SyncSettings { Layout.fillWidth: true }
        }
    }
    Component {
        id: category5
        ColumnLayout {
            spacing: 16
            BackupSettings { Layout.fillWidth: true }
        }
    }
    Component {
        id: category6
        ColumnLayout {
            spacing: 16
            KeyboardShortcuts { Layout.fillWidth: true }
        }
    }
    Component {
        id: category7
        ColumnLayout {
            id: aboutSection
            spacing: 16
            property string resetStatus: ""
            Component.onDestruction: pane.modalActive = false
            Label {
                text: "About"
                color: Theme.text
                font.pixelSize: 20
                font.weight: Font.DemiBold
            }
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: updateCard.implicitHeight + 36
                radius: 10
                color: Theme.surface
                border.width: 1
                border.color: Theme.separator
                ColumnLayout {
                    id: updateCard
                    anchors.fill: parent
                    anchors.margins: 18
                    spacing: 12
                    Label {
                        text: "Updates"
                        color: Theme.text
                        font.pixelSize: 15
                        font.weight: Font.Medium
                    }
                    Label {
                        text: "Checks GitHub releases for newer versions. Updates are never downloaded automatically."
                        color: Theme.muted
                        font.pixelSize: 12
                        wrapMode: Text.WordWrap
                        Layout.fillWidth: true
                    }
                    AppCheck {
                        text: "Notify about updates"
                        checked: app.settings.updateNotifications !== false
                        onToggled: app.setSetting("updateNotifications", checked)
                    }
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 10
                        FlatButton {
                            text: app.updateInfo.checking ? "Checking…" : "Check for updates"
                            glyph: "rotate-ccw"
                            outlined: true
                            enabled: !app.updateInfo.checking
                            onClicked: app.checkForUpdates(true)
                        }
                        FlatButton {
                            visible: !!app.updateInfo.available
                            text: "View v" + (app.updateInfo.version || "")
                            glyph: "external-link"
                            primary: true
                            onClicked: app.openReleasePage()
                        }
                    }
                    Label {
                        visible: !!(app.updateInfo.status)
                        text: app.updateInfo.status || ""
                        color: app.updateInfo.available ? Theme.accent : Theme.muted
                        font.pixelSize: 12
                        wrapMode: Text.WordWrap
                        Layout.fillWidth: true
                    }
                }
            }
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: aboutCard.implicitHeight + 40
                radius: 10
                color: Theme.surface
                border.width: 1
                border.color: Theme.separator
                ColumnLayout {
                    id: aboutCard
                    anchors.fill: parent
                    anchors.margins: 20
                    spacing: 16
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 18
                        Image {
                            source: "qrc:/assets/brand/silly-cat.png"
                            sourceSize: Qt.size(256, 256)
                            smooth: true
                            mipmap: true
                            Layout.preferredWidth: 80
                            Layout.preferredHeight: 80
                            fillMode: Image.PreserveAspectFit
                        }
                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 4
                            Label {
                                text: "SillyPlayer"
                                color: Theme.text
                                font.pixelSize: 18
                                font.weight: Font.DemiBold
                            }
                            Label {
                                text: "Silly Cat Productions"
                                color: Theme.text
                                font.pixelSize: 13
                            }
                            Label {
                                text: "Version " + Qt.application.version
                                color: Theme.muted
                                font.pixelSize: 12
                            }
                        }
                    }
                    Rectangle { Layout.fillWidth: true; implicitHeight: 1; color: Theme.separator }
                    AppCheck {
                        objectName: "developerModeEnabled"
                        text: "Developer mode"
                        checked: !!app.settings.developerMode
                        onToggled: app.setSetting("developerMode", checked)
                    }
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 10
                        FlatButton {
                            text: "Exit SillyPlayer"
                            glyph: "x"
                            outlined: true
                            danger: true
                            onClicked: Qt.quit()
                        }
                    }
                }
            }
            Rectangle {
                visible: !!app.settings.developerMode
                Layout.fillWidth: true
                implicitHeight: devCard.implicitHeight + 36
                radius: 10
                color: Theme.surface
                border.width: 1
                border.color: Theme.separator
                ColumnLayout {
                    id: devCard
                    anchors.fill: parent
                    anchors.margins: 18
                    spacing: 12
                    Label {
                        text: "Developer tools"
                        color: Theme.text
                        font.pixelSize: 15
                        font.weight: Font.Medium
                    }
                    Label {
                        text: "Reset only this computer's local library records. Audio files on disk and paired phones are kept."
                        color: Theme.muted
                        font.pixelSize: 12
                        wrapMode: Text.WordWrap
                        Layout.fillWidth: true
                    }
                    FlatButton {
                        objectName: "resetDesktopLibraryButton"
                        text: "Reset desktop library"
                        glyph: "trash-2"
                        outlined: true
                        danger: true
                        enabled: !library.scanning && !library.trashing && !app.sync.busy && !app.backup.busy
                        onClicked: {
                            resetConfirmField.text = "";
                            resetRemoveCache.checked = false;
                            resetLibraryDialog.open();
                        }
                    }
                    Label {
                        visible: !!aboutSection.resetStatus
                        text: aboutSection.resetStatus
                        color: Theme.muted
                        font.pixelSize: 12
                        wrapMode: Text.WordWrap
                        Layout.fillWidth: true
                    }
                }
            }
            AppDialog {
                id: resetLibraryDialog
                objectName: "resetDesktopLibraryDialog"
                anchors.centerIn: Overlay.overlay
                width: Math.min(pane.width - 40, 460)
                title: "Reset desktop library?"
                standardButtons: Dialog.Cancel
                onOpenedChanged: pane.modalActive = opened
                ColumnLayout {
                    width: parent.width
                    spacing: 12
                    Label {
                        text: "Removes songs, playlists, likes, listening and download history, watched folders, and the playback queue from this computer. Original audio files on disk are kept, and paired phones stay paired with reset sync cursors."
                        color: Theme.muted
                        font.pixelSize: 12
                        wrapMode: Text.WordWrap
                        Layout.fillWidth: true
                    }
                    AppCheck {
                        id: resetRemoveCache
                        text: "Also delete cached artwork and synced files"
                        checked: false
                    }
                    AppField {
                        id: resetConfirmField
                        placeholderText: "Type RESET"
                        Layout.fillWidth: true
                    }
                    FlatButton {
                        text: "Reset desktop library"
                        glyph: "trash-2"
                        danger: true
                        primary: true
                        enabled: resetConfirmField.text === "RESET"
                        Layout.alignment: Qt.AlignRight
                        onClicked: {
                            const res = app.resetDesktopLibrary(resetRemoveCache.checked);
                            aboutSection.resetStatus = res.message || "";
                            resetLibraryDialog.close();
                        }
                    }
                }
            }
        }
    }
}
