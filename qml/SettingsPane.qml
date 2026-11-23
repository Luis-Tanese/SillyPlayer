import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Item {
    id: pane
    objectName: "settingsPane"
    required property var shell
    property string category: "Appearance"
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
                            currentIndex: model.indexOf(app.settings.palette)
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
                        text: "Timer & transitions"
                        color: Theme.text
                        font.pixelSize: 15
                        font.weight: Font.Medium
                    }
                    FlatButton {
                        text: app.sleepTimer.active ? "Sleep timer · " + app.time(app.sleepTimer.remainingSeconds) : "Sleep timer"
                        glyph: "clock"
                        outlined: true
                        checked: app.sleepTimer.active
                        onClicked: pane.shell.showSleepTimer()
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
                    // todo: add options here for users to customize what discord rpc displays
                    Label {
                        text: "Displays the current song, artist, playback progress, and cover art on your Discord profile when Discord is running."
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
                            glyph: "plus"
                            outlined: true
                            onClicked: {
                                let path = app.chooseFolder();
                                if (path)
                                    library.addFolder(path);
                            }
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
                        FlatButton { text: "Open system Trash"; glyph: "trash-2"; outlined: true; onClicked: app.openTrash() }
                    }
                }
            }
            AppDialog {
                id: clearFolderDialog
                anchors.centerIn: Overlay.overlay
                width: Math.min(pane.width - 40, 460)
                title: "Clear music folder?"
                standardButtons: Dialog.Cancel
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
            spacing: 16
            Label {
                text: "About"
                color: Theme.text
                font.pixelSize: 20
                font.weight: Font.DemiBold
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
        }
    }
}
