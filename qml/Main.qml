import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Window
import SillyPlayer 1.0

ApplicationWindow {
    id: root
    width: 1160
    height: 800
    minimumWidth: 620
    minimumHeight: 540
    visible: true
    title: "SillyPlayer"
    color: Theme.bg
    property string page: "Music"
    property bool geometryReady: false
    Component.onCompleted: {
        // restore lastPage once so later settingsChanged signals cant snap the active page back
        if (["Music", "Browse", "Split", "Downloads", "Me!", "Settings"].includes(app.settings.lastPage))
            page = app.settings.lastPage;
        // restore window geometry once so later setting changes cannot snap a maximized window back
        width = Math.max(minimumWidth, Number(app.settings.windowWidth) || 1160);
        height = Math.max(minimumHeight, Number(app.settings.windowHeight) || 800);
        const sx = Number(app.settings.windowX);
        const sy = Number(app.settings.windowY);
        if (isFinite(sx) && isFinite(sy) && sx >= Screen.virtualX && sy >= Screen.virtualY &&
            sx < Screen.virtualX + Screen.desktopAvailableWidth - 100 &&
            sy < Screen.virtualY + Screen.desktopAvailableHeight - 100) {
            x = sx;
            y = sy;
        }
        if (app.settings.windowMaximized)
            showMaximized();
        Qt.callLater(function() { geometryReady = true; });
    }
    Timer {
        id: windowStateSaver
        interval: 450
        onTriggered: {
            if (!root.geometryReady) return;
            if (root.visibility === Window.Windowed || root.visibility === Window.Maximized) {
                const maximized = root.visibility === Window.Maximized;
                if (Boolean(app.settings.windowMaximized) !== maximized)
                    app.setSetting("windowMaximized", maximized);
            }
            if (root.visibility === Window.Windowed) {
                if (Number(app.settings.windowWidth) !== root.width)
                    app.setSetting("windowWidth", root.width);
                if (Number(app.settings.windowHeight) !== root.height)
                    app.setSetting("windowHeight", root.height);
                if (Number(app.settings.windowX) !== root.x)
                    app.setSetting("windowX", root.x);
                if (Number(app.settings.windowY) !== root.y)
                    app.setSetting("windowY", root.y);
            }
            if (app.settings.lastPage !== root.page)
                app.setSetting("lastPage", root.page);
        }
    }
    onWidthChanged: {
        if (geometryReady && visibility === Window.Windowed) windowStateSaver.restart();
        if (page === "Split" && width < 960 && narrowPane.currentIndex === 0) {
            online.cancelResolution();
            youtubePlayer.pausePlayback();
        }
    }
    onHeightChanged: if (geometryReady && visibility === Window.Windowed) windowStateSaver.restart()
    onXChanged: if (geometryReady && visibility === Window.Windowed) windowStateSaver.restart()
    onYChanged: if (geometryReady && visibility === Window.Windowed) windowStateSaver.restart()
    onVisibilityChanged: if (geometryReady && (visibility === Window.Windowed || visibility === Window.Maximized)) windowStateSaver.restart()
    property bool expanded: false
    property bool updateBannerDismissed: false
    property string returnPage: "Music"
    property bool videoOpen: false
    property var videoDetails: ({})
    property var selectedTrack: ({})
    property var selectedCollection: ({})
    property string collectionKind: "playlist"
    property bool renameMode: false
    property var downloadDetails: ({})
    property string addSongsCollection: ""
    property string duplicatePath: ""
    property bool editingText: activeFocusItem && (activeFocusItem.hasOwnProperty("selectedText") || activeFocusItem.hasOwnProperty("inputMethodComposing"))
    property bool videoKeyboard: youtubePane.activeFocus && videoOpen && (page === "Browse" || page === "Split")
    property bool videoVisible: videoOpen && !expanded && (page === "Browse" || (page === "Split" && (width >= 960 || narrowPane.currentIndex === 1)))
    property bool playerKeyboard: expanded || compactPlayer.activeFocus || videoKeyboard
    // track settingsPane modals too so global shortcuts stay paused when clear/reset dialogs are open
    property bool modalActive: libraryToolsDialog.opened || playlistDownloadDialog.opened || sleepDialog.opened || musicPane.bulkModalActive || settingsPane.modalActive || errorDialog.opened || nameDialog.opened || deleteDialog.opened || removeDuplicatesDialog.opened || addDialog.opened || metadataDialog.opened || catalogueDialog.opened || infoDialog.opened || speedDialog.opened || songVolumeDialog.opened || eqDialog.opened || downloadDialog.opened || duplicateDialog.opened || addSongsDialog.opened || detailsDialog.opened || trashDialog.opened || linkDialog.opened || songMenu.opened || expandedMenu.opened || collectionActions.opened
    property var orderedJobs: online.jobs.slice().sort((a, b) => {
            const states = ["Active", "Resolving", "Post-processing", "Queued", "Paused", "Interrupted", "Failed", "Completed", "Cancelled"];
            return states.indexOf(a.state) - states.indexOf(b.state);
        })
    palette.window: Theme.bg
    palette.windowText: Theme.text
    palette.base: Theme.base
    palette.alternateBase: Theme.hover
    palette.text: Theme.text
    palette.button: Theme.hover
    palette.buttonText: Theme.text
    palette.highlight: Theme.accent
    palette.highlightedText: Theme.bg
    palette.placeholderText: Theme.muted
    palette.mid: Theme.hover
    palette.dark: Theme.base
    palette.midlight: Theme.rail
    palette.light: Theme.surface
    palette.shadow: Theme.base
    palette.brightText: Theme.text
    palette.toolTipBase: Theme.surface
    palette.toolTipText: Theme.text
    palette.link: Theme.accent
    palette.linkVisited: Theme.accent
    font.family: "Noto Sans"
    font.pixelSize: 13
    function navigate(target) {
        if (target !== "Browse" && target !== "Split")
            youtubePlayer.pausePlayback();
        if (expanded)
            expanded = false;
        if (page !== target) { musicPane.clearSelection(); musicPane.cancelReorder(); }
        page = target;
    }
    function expand() {
        if (!app.current.id)
            return;
        returnPage = page;
        page = "Music";
        expanded = true;
        youtubePlayer.pausePlayback();
    }
    function collapse() {
        expanded = false;
        page = returnPage;
    }
    function closeVideo() {
        online.cancelResolution();
        videoOpen = false;
        youtubePlayer.pausePlayback();
    }
    function toggleQueue() {
        queueDrawer.opened ? queueDrawer.close() : queueDrawer.open();
    }
    function trackMenu(track, anchor) {
        selectedTrack = track;
        songMenu.popup(anchor, 0, anchor.height);
    }
    function playerMenu(anchor) {
        selectedTrack = app.current;
        expandedMenu.popup(anchor, 0, anchor.height);
    }
    function collectionMenu(collection, anchor) {
        selectedCollection = collection;
        collectionActions.popup(anchor, 0, anchor.height);
    }
    function newCollection(kind) {
        collectionKind = kind;
        renameMode = false;
        nameInput.text = "";
        nameDialog.open();
    }
    function openArtist(artist) {
        navigate("Music");
        musicPane.tab = "Artists";
        musicPane.collectionId = artist.id;
        musicPane.collectionName = artist.name;
    }
    function download(details) {
        downloadDetails = details;
        formatBox.currentIndex = 0;
        destination.text = app.settings.musicDir;
        downloadDialog.open();
    }
    function startDownload(again) {
        let result = online.enqueue(downloadDetails, downloadDetails.provider === "direct" || formatBox.currentIndex === 0 ? "Original" : "MP3", "best", destination.text, again);
        if (result.startsWith("duplicate:")) {
            duplicatePath = result.slice(10);
            duplicateDialog.open();
        } else if (result) {
            downloadDialog.close();
        }
    }
    function editSpeed(track) {
        const saved = library.track(track.id);
        if (!saved.id) return;
        selectedTrack = saved;
        speedDialog.trackId = saved.id;
        rate.value = saved.speed || 1.0;
        nightcore.checked = !!saved.nightcore;
        speedDialog.open();
    }
    function editVolume(track) {
        const saved = library.track(track.id);
        if (!saved.id) return;
        selectedTrack = saved;
        songVolumeDialog.show(saved);
    }
    function editMetadata() {
        titleInput.text = selectedTrack.title || "";
        artistInput.text = selectedTrack.artist || "";
        albumInput.text = selectedTrack.album || "";
        albumArtistInput.text = selectedTrack.albumArtist || "";
        metadataDialog.coverPath = "";
        metadataDialog.open();
    }
    function showInfo() {
        infoDialog.open();
    }
    onClosing: function (close) {
        close.accepted = false;
        if (trayAvailable)
            hide();
        else
            showMinimized();
    }
    onPageChanged: {
        if (geometryReady) windowStateSaver.restart();
        if ((page !== "Browse" && page !== "Split") || (page === "Split" && width < 960 && narrowPane.currentIndex === 0)) {
            online.cancelResolution();
            youtubePlayer.pausePlayback();
        }
    }
    Connections {
        target: app
        function onError(message) {
            errorLabel.text = message;
            errorDialog.open();
        }
    }
    Connections {
        target: music
        function onChanged() {
            if (music.error) {
                errorLabel.text = music.error;
            }
        }
    }
    Connections {
        target: online
        function onPlaybackRequested(refresh) {
            if (!refresh) {
                root.videoDetails = ({});
                root.videoOpen = true;
            }
        }
        function onDownloadReady(details) {
            root.download(details);
        }
        function onStreamReady(url, audio, headers) {
            root.videoDetails = online.details;
            if (!root.videoVisible)
                youtubePlayer.pausePlayback();
        }
    }

    function showSleepTimer() { sleepDialog.open(); }
    function openEqualizer() { eqDialog.open(); }
    SleepDialog { id: sleepDialog }
    PlaylistDownloadDialog { id: playlistDownloadDialog }
    LibraryToolsDialog { id: libraryToolsDialog }

    Shortcut {
        sequence: "Space"
        enabled: !root.editingText && !root.modalActive && !root.videoKeyboard
        onActivated: app.toggle()
    }
    Shortcut {
        sequence: "Space"
        enabled: !root.editingText && !root.modalActive && root.videoKeyboard
        onActivated: youtubePlayer.toggle()
    }
    Shortcut {
        sequence: "Ctrl+Left"
        enabled: !root.editingText && !root.modalActive
        onActivated: app.previous()
    }
    Shortcut {
        sequence: "Ctrl+Right"
        enabled: !root.editingText && !root.modalActive
        onActivated: app.next()
    }
    function focusQuickSearch() {
        if (expanded) expanded = false;
        if (page === "Browse" || (page === "Split" && youtubePane.activeFocus)) {
            youtubePane.focusSearch();
        } else {
            if (page !== "Music" && page !== "Split") page = "Music";
            musicPane.focusSearch();
        }
    }
    Shortcut {
        sequence: "Ctrl+K"
        enabled: !root.modalActive
        onActivated: root.focusQuickSearch()
    }
    Shortcut {
        sequence: "Ctrl+F"
        enabled: !root.modalActive
        onActivated: root.focusQuickSearch()
    }
    Shortcut {
        sequence: "Ctrl+J"
        enabled: !root.modalActive
        onActivated: root.toggleQueue()
    }
    Shortcut {
        sequence: "M"
        enabled: !root.editingText && !root.modalActive
        onActivated: {
            let p = root.videoKeyboard ? youtubePlayer : music;
            p.volume = p.volume > 0 ? 0 : 70;
        }
    }
    Shortcut {
        sequence: "Left"
        enabled: !root.editingText && !root.modalActive && root.playerKeyboard
        onActivated: {
            let p = root.videoKeyboard ? youtubePlayer : music;
            p.seek(p.position - 5);
        }
    }
    Shortcut {
        sequence: "Right"
        enabled: !root.editingText && !root.modalActive && root.playerKeyboard
        onActivated: {
            let p = root.videoKeyboard ? youtubePlayer : music;
            p.seek(p.position + 5);
        }
    }
    Shortcut {
        sequence: "Up"
        enabled: !root.editingText && !root.modalActive && root.playerKeyboard
        onActivated: {
            let p = root.videoKeyboard ? youtubePlayer : music;
            p.volume = Math.min(100, p.volume + 5);
        }
    }
    Shortcut {
        sequence: "Down"
        enabled: !root.editingText && !root.modalActive && root.playerKeyboard
        onActivated: {
            let p = root.videoKeyboard ? youtubePlayer : music;
            p.volume = Math.max(0, p.volume - 5);
        }
    }
    Shortcut {
        sequence: "Ctrl+A"
        enabled: !root.editingText && !root.modalActive && !root.expanded && (root.page === "Music" || root.page === "Split") && musicPane.listKeyboard
        onActivated: musicPane.selectAll()
    }
    Shortcut {
        sequence: "Escape"
        enabled: !root.modalActive
        onActivated: {
            if (mePane.timeTooltipOpen)
                mePane.dismissTimeTooltip();
            else if (root.visibility === Window.FullScreen)
                root.showNormal();
            else if (queueDrawer.opened)
                queueDrawer.close();
            else if (root.expanded)
                root.collapse();
            else if (musicPane.dragMember)
                musicPane.cancelReorder();
            else if (musicPane.selectionMode)
                musicPane.clearSelection();
            else if (root.videoOpen) {
                root.closeVideo();
            }
        }
    }

    header: Item {
        height: 68
        Rectangle {
            anchors.bottom: parent.bottom
            height: 1
            width: parent.width
            color: Theme.separator
        }
        RowLayout {
            anchors.fill: parent
            anchors.leftMargin: 28
            anchors.rightMargin: 28
            spacing: 5
            Image {
                source: "qrc:/assets/sillyplayer.png"
                sourceSize: Qt.size(128, 128)
                smooth: true
                mipmap: true
                Layout.preferredWidth: 44
                Layout.preferredHeight: 44
                fillMode: Image.PreserveAspectFit
            }
            Label {
                text: "SillyPlayer"
                color: Theme.text
                font.weight: Font.DemiBold
                font.pixelSize: 14
                Layout.rightMargin: 30
                visible: root.width > 820
            }
            Repeater {
                model: ["Music", "Browse", "Split", "Downloads"]
                FlatButton {
                    required property string modelData
                    text: modelData
                    navigation: true
                    checked: root.page === modelData
                    onClicked: root.navigate(modelData)
                }
            }
            Item {
                Layout.fillWidth: true
            }
            FlatButton {
                objectName: "meNavigation"
                text: "Me!"
                navigation: true
                checked: root.page === "Me!"
                onClicked: root.navigate("Me!")
            }
            FlatButton {
                glyph: "settings"
                hint: "Settings"
                checked: root.page === "Settings"
                onClicked: root.navigate("Settings")
            }
        }
    }
    ColumnLayout {
        anchors.fill: parent
        spacing: 0
        Rectangle {
            Layout.fillWidth: true
            visible: !!app.updateInfo.available && app.settings.updateNotifications !== false && !root.updateBannerDismissed
            implicitHeight: visible ? 40 : 0
            color: Qt.alpha(Theme.accent, 0.14)
            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 28
                anchors.rightMargin: 20
                spacing: 10
                Label {
                    text: "SillyPlayer v" + (app.updateInfo.version || "") + " is available"
                    color: Theme.text
                    font.pixelSize: 12
                    font.weight: Font.Medium
                    Layout.fillWidth: true
                }
                FlatButton {
                    text: "View release"
                    glyph: "external-link"
                    primary: true
                    onClicked: app.openReleasePage()
                }
                FlatButton {
                    glyph: "x"
                    hint: "Dismiss update notice"
                    onClicked: root.updateBannerDismissed = true
                }
            }
            Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.separator }
        }
        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true
            ColumnLayout {
                anchors.fill: parent
                spacing: 0
                visible: root.page === "Music" || root.page === "Browse" || root.page === "Split"
                property bool narrow: root.page === "Split" && root.width < 960
                RowLayout {
                    visible: parent.narrow
                    Layout.alignment: Qt.AlignHCenter
                    FlatButton {
                        text: "Music"
                        checked: narrowPane.currentIndex === 0
                        onClicked: narrowPane.currentIndex = 0
                    }
                    FlatButton {
                        text: "Browse"
                        checked: narrowPane.currentIndex === 1
                        onClicked: narrowPane.currentIndex = 1
                    }
                    Label {
                        text: "Widen window for both panels"
                        color: Theme.muted
                        font.pixelSize: 11
                    }
                }
                QtObject {
                    id: narrowPane
                    property int currentIndex: 0
                    onCurrentIndexChanged: if (currentIndex === 0 && root.width < 960 && root.page === "Split") {
                        online.cancelResolution();
                        youtubePlayer.pausePlayback();
                    }
                }
                SplitView {
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    orientation: Qt.Horizontal
                    handle: Rectangle {
                        objectName: "musicSplitHandle"
                        implicitWidth: 5
                        color: SplitHandle.hovered || SplitHandle.pressed ? Theme.accent : Theme.hover
                        opacity: 0.5
                    }
                    MusicPane {
                        id: musicPane
                        shell: root
                        SplitView.preferredWidth: root.width * 0.48
                        SplitView.minimumWidth: 340
                        visible: root.page === "Music" || (root.page === "Split" && (root.width >= 960 || narrowPane.currentIndex === 0))
                    }
                    YouTubePane {
                        id: youtubePane
                        shell: root
                        SplitView.fillWidth: true
                        SplitView.minimumWidth: 340
                        visible: root.page === "Browse" || (root.page === "Split" && (root.width >= 960 || narrowPane.currentIndex === 1))
                    }
                }
            }
            MePane {
                id: mePane
                objectName: "mePane"
                anchors.fill: parent
                visible: root.page === "Me!"
                shell: root
            }
            SettingsPane {
                id: settingsPane
                anchors.fill: parent
                anchors.margins: root.width > 876 ? 28 : 20
                visible: root.page === "Settings"
                shell: root
            }
            ColumnLayout {
                anchors.fill: parent
                anchors.margins: 28
                visible: root.page === "Downloads"
                spacing: 16
                RowLayout {
                    Layout.fillWidth: true
                    Label {
                        text: "Downloads"
                        color: Theme.text
                        font.pixelSize: 22
                    }
                    Item {
                        Layout.fillWidth: true
                    }
                    FlatButton {
                        text: root.width > 750 ? "Clear failed" : "Failed"; glyph: "trash-2"
                        enabled: online.jobs.some(j => j.state === "Failed")
                        onClicked: online.clearFailed()
                    }
                    FlatButton {
                        text: root.width > 750 ? "Clear completed" : "Completed"
                        enabled: online.jobs.some(j => j.state === "Completed" || j.state === "Cancelled")
                        onClicked: online.clearCompleted()
                    }
                }
                ListView {
                    id: jobsView
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    model: StableListModel {
                        rows: root.orderedJobs
                    }
                    clip: true
                    spacing: 12
                    ScrollBar.vertical: ScrollBar {
                    }
                    Label {
                        anchors.centerIn: parent
                        visible: jobsView.count === 0
                        text: "Downloads you start appear here."
                        color: Theme.muted
                    }
                    delegate: ColumnLayout {
                        required property var record
                        property var modelData: record
                        required property int index
                        width: jobsView.width
                        spacing: 6
                        Label {
                            visible: index === 0 || (index > 0 && index < root.orderedJobs.length && root.orderedJobs[index - 1] !== undefined && root.orderedJobs[index - 1].state !== modelData.state)
                            text: modelData.state
                            color: Theme.muted
                            font.pixelSize: 12
                            Layout.topMargin: 8
                        }
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 14
                            ColumnLayout {
                                Layout.fillWidth: true
                                Label {
                                    text: modelData.title
                                    color: Theme.text
                                    elide: Text.ElideRight
                                    Layout.fillWidth: true
                                }
                                Label {
                                    text: (modelData.provider === "soundcloud" ? "SoundCloud · " : modelData.provider === "direct" ? "Direct Link · " : "YouTube · ") + modelData.kind + " · " + modelData.destination
                                    color: Theme.muted
                                    elide: Text.ElideMiddle
                                    font.pixelSize: 11
                                    Layout.fillWidth: true
                                }
                            }
                            FlatButton {
                                glyph: "pause"
                                hint: "Pause transfer"
                                visible: modelData.state === "Active" || modelData.state === "Resolving"
                                onClicked: online.pause(modelData.id)
                            }
                            FlatButton {
                                glyph: "x"
                                hint: "Cancel download"
                                visible: ["Queued", "Active", "Resolving", "Post-processing", "Paused"].includes(modelData.state)
                                onClicked: online.cancel(modelData.id)
                            }
                            FlatButton {
                                glyph: "rotate-ccw"
                                hint: modelData.state === "Paused" ? "Resume" : "Retry"
                                visible: modelData.kind !== "Video" && ["Paused", "Interrupted", "Failed", "Cancelled"].includes(modelData.state)
                                onClicked: online.retry(modelData.id)
                            }
                            FlatButton {
                                glyph: "trash-2"; hint: "Remove history entry (keep media)"
                                visible: ["Failed", "Completed", "Cancelled", "Interrupted", "Paused"].includes(modelData.state)
                                onClicked: online.removeJob(modelData.id)
                            }
                            FlatButton {
                                glyph: "external-link"
                                hint: "Open file"
                                visible: modelData.state === "Completed"
                                onClicked: app.openFile(modelData.path)
                            }
                            FlatButton {
                                glyph: "folder"
                                hint: "Open containing folder"
                                visible: modelData.state === "Completed"
                                onClicked: app.openFile(modelData.path, true)
                            }
                        }
                        AppProgress {
                            Layout.fillWidth: true
                            visible: ["Active", "Resolving", "Post-processing"].includes(modelData.state)
                            from: 0
                            to: 100
                            value: Math.max(0, modelData.progress)
                            indeterminate: modelData.progress < 0
                        }
                        Label {
                            visible: !!modelData.stage
                            text: modelData.stage || ""
                            color: Theme.muted
                            font.pixelSize: 11
                        }
                        RowLayout {
                            Layout.fillWidth: true
                            visible: !!modelData.error || (modelData.kind === "Video" && modelData.state !== "Completed")
                            Label {
                                Layout.fillWidth: true
                                text: modelData.kind === "Video" ? "Video downloads are no longer supported" : (modelData.error || "").split("\n").filter(l => l.includes("ERROR:")).slice(-1)[0] || (modelData.error || "Download failed").split("\n")[0]
                                color: Theme.danger; elide: Text.ElideRight; font.pixelSize: 11
                            }
                            FlatButton { text: "Details"; glyph: "info"; onClicked: { technicalDetails.text = modelData.error || "Video history is preserved. Existing files can still be opened."; detailsDialog.open(); } }
                        }
                    }
                }
            }
        }
        PlayerBar {
            id: compactPlayer
            Layout.fillWidth: true
            visible: !root.expanded
            shell: root
        }
    }
    Drawer {
        id: queueDrawer
        edge: Qt.RightEdge
        width: Math.min(root.width - 40, 410)
        height: root.height
        background: Rectangle {
            color: Theme.base
        }
        ColumnLayout {
            anchors.fill: parent
            anchors.margins: 20
            spacing: 12
            RowLayout {
                Layout.fillWidth: true
                Label {
                    text: "Playback queue"
                    color: Theme.text
                    font.pixelSize: 19
                    Layout.fillWidth: true
                }
                FlatButton {
                    glyph: "x"
                    hint: "Close queue"
                    onClicked: queueDrawer.close()
                }
            }
            Label {
                text: app.context
                color: Theme.muted
                Layout.fillWidth: true
                elide: Text.ElideRight
            }
            FlatButton {
                text: "Clear upcoming"
                enabled: queueDrawer.visible && app.queue.some(q => !q.passed && !q.isCurrent)
                onClicked: app.clearUpcoming()
            }
            ListView {
                id: queueList
                Layout.fillWidth: true
                Layout.fillHeight: true
                model: StableListModel {
                    id: playbackQueueModel
                    rows: queueDrawer.visible ? app.queue : []
                }
                Connections { target: library; function onTracksUpdated(patches) { playbackQueueModel.patchTracks(patches); } }
                clip: true
                ScrollBar.vertical: ScrollBar {
                }
                delegate: Rectangle {
                    required property var record
                    property var modelData: record
                    required property int index
                    width: queueList.width
                    height: 64
                    color: modelData.isCurrent ? Theme.hover : "transparent"
                    RowLayout {
                        anchors.fill: parent
                        spacing: 8
                        FlatButton {
                            id: dragGrip
                            objectName: "queueGrip-" + index
                            glyph: "grip-vertical"
                            hint: "Drag to reorder"
                            property int origin: -1
                            DragHandler {
                                id: reorder
                                target: null
                                onActiveChanged: {
                                    if (active)
                                        dragGrip.origin = index;
                                    else if (dragGrip.origin >= 0 && queueDrawer.visible) {
                                        app.moveQueue(dragGrip.origin, Math.max(0, Math.min(queueList.count - 1, dragGrip.origin + Math.round(translation.y / 64))));
                                        dragGrip.origin = -1;
                                    }
                                }
                            }
                        }
                        Art {
                            source: modelData.art || ""
                            implicitWidth: 36
                            implicitHeight: 36
                        }
                        FlatButton {
                            Layout.fillWidth: true
                            Layout.minimumWidth: 0
                            implicitHeight: 54
                            onClicked: app.queuePlay(modelData.entryId)
                            contentItem: Column {
                                spacing: 3
                                Label {
                                    width: parent.width
                                    text: (modelData.isCurrent ? "Playing · " : "") + modelData.title
                                    elide: Text.ElideRight
                                    color: modelData.isCurrent ? Theme.accent : Theme.text
                                    font.pixelSize: 12
                                }
                                Label {
                                    width: parent.width
                                    text: modelData.artist || "Unknown artist"
                                    elide: Text.ElideRight
                                    color: Theme.muted
                                    font.pixelSize: 11
                                }
                            }
                        }
                        FlatButton {
                            glyph: "x"
                            hint: "Remove from queue"
                            enabled: !modelData.isCurrent
                            onClicked: app.removeQueue(modelData.entryId)
                        }
                    }
                }
            }
        }
    }
    AppMenu {
        id: songMenu
        AppMenuItem {
            text: "Play Next"
            glyph: "skip-forward"
            onTriggered: app.playNext(root.selectedTrack.id)
        }
        AppMenuItem {
            text: "Playback Speed"; glyph: "play"
            objectName: "songSpeedAction"
            onTriggered: root.editSpeed(root.selectedTrack)
        }
        AppMenuItem {
            objectName: "songVolumeAction"
            text: "Song volume"; glyph: "volume-2"
            onTriggered: root.editVolume(root.selectedTrack)
        }
        AppMenuItem {
            objectName: "songEqualizerAction"
            text: "Equalizer"; glyph: "settings"
            onTriggered: eqDialog.open()
        }
        AppMenuItem {
            text: "Add to Playlist"
            glyph: "list-music"
            onTriggered: addDialog.open()
        }
        AppMenuItem {
            glyph: "heart"
            text: root.selectedTrack.liked ? "Remove from Liked Songs" : "Add to Liked Songs"
            onTriggered: app.like(root.selectedTrack.id)
        }
        AppMenuItem {
            text: "Remove from collection"
            glyph: "x"
            visible: root.page !== "Me!" && !!musicPane.collectionId && musicPane.collectionId !== "liked" && musicPane.collectionId !== "recent" && !musicPane.collectionId.startsWith("album:") && !musicPane.collectionId.startsWith("artist:") && !musicPane.collectionId.startsWith("folder:")
            height: visible ? implicitHeight : 0
            onTriggered: app.removeMember(musicPane.collectionId, root.selectedTrack.memberId)
        }
        AppMenuItem {
            text: "Edit Metadata"
            glyph: "pencil"
            onTriggered: root.editMetadata()
        }
        AppMenuItem {
            text: "Information"
            glyph: "info"
            onTriggered: root.showInfo()
        }
        AppMenuItem {
            objectName: "songLinkAction"
            text: "Link"; glyph: "external-link"
            onTriggered: linkDialog.open()
        }
        AppMenuItem {
            text: "Move to Trash"; glyph: "trash-2"
            onTriggered: trashDialog.open()
        }
    }
    AppMenu {
        id: expandedMenu
        AppMenuItem { text: "Sleep timer"; glyph: "clock"; onTriggered: root.showSleepTimer() }
        AppMenuItem {
            text: "Link"; glyph: "external-link"
            onTriggered: { root.selectedTrack = app.current; linkDialog.open(); }
        }
        AppMenuItem {
            text: "Playback Speed"
            glyph: "play"
            onTriggered: root.editSpeed(app.current)
        }
        AppMenuItem {
            objectName: "expandedSongVolumeAction"
            text: "Song volume"; glyph: "volume-2"
            onTriggered: root.editVolume(app.current)
        }
        AppMenuItem {
            text: "Equalizer"
            glyph: "settings"
            onTriggered: eqDialog.open()
        }
        AppMenuItem {
            text: "Add to Playlist"
            glyph: "list-music"
            onTriggered: addDialog.open()
        }
        AppMenuItem {
            text: "Edit Metadata"
            glyph: "pencil"
            onTriggered: root.editMetadata()
        }
        AppMenuItem {
            text: "Information"
            glyph: "info"
            onTriggered: root.showInfo()
        }
        AppMenuItem {
            text: "Move to Trash"; glyph: "trash-2"
            onTriggered: { root.selectedTrack = app.current; trashDialog.open(); }
        }
    }
    AppMenu {
        id: collectionActions
        AppMenuItem {
            text: root.selectedCollection.kind === "artist"
                ? (root.selectedCollection.pinned ? "Unpin Artist" : "Pin Artist")
                : (root.selectedCollection.pinned ? "Unpin Playlist" : "Pin Playlist")
            glyph: "pin"
            visible: root.selectedCollection.kind === "playlist" || root.selectedCollection.kind === "artist"
            height: visible ? implicitHeight : 0
            onTriggered: app.togglePin(root.selectedCollection.kind, root.selectedCollection.id)
        }
        AppMenuItem {
            text: root.selectedCollection.kind === "custom" ? "Edit Custom Album" : "Edit Playlist"
            glyph: "pencil"
            visible: root.selectedCollection.kind !== "artist"
            height: visible ? implicitHeight : 0
            onTriggered: {
                root.renameMode = true;
                nameInput.text = root.selectedCollection.name;
                nameDialog.open();
            }
        }
        AppMenuItem {
            text: "Change Cover"
            glyph: "image"
            visible: root.selectedCollection.kind !== "artist"
            height: visible ? implicitHeight : 0
            onTriggered: app.coverCollection(root.selectedCollection.id)
        }
        AppMenuItem {
            text: root.selectedCollection.kind === "custom" ? "Export album (.m3u8)" : "Export playlist (.m3u8)"
            glyph: "download"
            visible: root.selectedCollection.kind !== "artist"
            height: visible ? implicitHeight : 0
            onTriggered: {
                const res = app.exportPlaylistM3u(root.selectedCollection.id);
                if (res && res.message) musicPane.resultMessage = res.message;
            }
        }
        AppMenuItem {
            objectName: "removePlaylistDuplicatesAction"
            text: "Remove duplicate songs"
            glyph: "list-checks"
            visible: root.selectedCollection.kind === "playlist" || root.selectedCollection.kind === "custom"
            height: visible ? implicitHeight : 0
            onTriggered: {
                const count = app.countPlaylistDuplicates(root.selectedCollection.id);
                if (count <= 0) {
                    musicPane.resultMessage = root.selectedCollection.kind === "custom" ? "No duplicate songs in this album" : "No duplicate songs in this playlist";
                    return;
                }
                removeDuplicatesDialog.duplicateCount = count;
                removeDuplicatesDialog.open();
            }
        }
        AppMenuItem {
            text: root.selectedCollection.kind === "custom" ? "Delete Custom Album" : "Delete Playlist"
            glyph: "trash-2"
            visible: root.selectedCollection.kind !== "artist"
            height: visible ? implicitHeight : 0
            onTriggered: deleteDialog.open()
        }
    }
    AppDialog {
        id: removeDuplicatesDialog
        objectName: "removeDuplicatesDialog"
        anchors.centerIn: parent
        width: Math.min(root.width - 40, 440)
        title: "Remove duplicate songs?"
        standardButtons: Dialog.Cancel
        property int duplicateCount: 0
        ColumnLayout {
            width: parent.width; spacing: 14
            Label {
                text: 'Remove ' + removeDuplicatesDialog.duplicateCount + (removeDuplicatesDialog.duplicateCount === 1 ? ' duplicate entry' : ' duplicate entries') + ' from "' + (root.selectedCollection.name || "") + '"?'
                color: Theme.text; Layout.fillWidth: true; wrapMode: Text.WordWrap
            }
            Label {
                text: "The first occurrence of each song is kept in playlist order."
                color: Theme.muted; font.pixelSize: 12; Layout.fillWidth: true; wrapMode: Text.WordWrap
            }
            FlatButton {
                objectName: "confirmRemovePlaylistDuplicates"
                text: "Remove duplicates"; glyph: "list-checks"; primary: true; Layout.alignment: Qt.AlignRight
                onClicked: {
                    const res = app.removePlaylistDuplicates(root.selectedCollection.id);
                    removeDuplicatesDialog.close();
                    if (res.status === "done")
                        musicPane.resultMessage = "Removed " + res.removed + (res.removed === 1 ? " duplicate song" : " duplicate songs");
                    else if (res.message) {
                        errorLabel.text = res.message; errorDialog.open();
                    }
                }
            }
        }
    }
    AppDialog {
        id: linkDialog
        objectName: "linkDialog"
        anchors.centerIn: parent
        width: Math.min(root.width - 40, 480)
        title: "Link"
        standardButtons: Dialog.Close
        property string url: ""
        property bool copied: false
        onAboutToShow: { url = app.sourceLink(root.selectedTrack.id || ""); copied = false; }
        ColumnLayout {
            width: parent.width; spacing: 14
            Label { text: root.selectedTrack.title || ""; color: Theme.text; Layout.fillWidth: true; elide: Text.ElideRight }
            AppField {
                objectName: "songLinkField"
                Layout.fillWidth: true
                text: linkDialog.url; readOnly: true; visible: !!linkDialog.url
                Accessible.name: "Source link"
            }
            Label {
                text: "No source link is saved for this song."
                color: Theme.muted; visible: !linkDialog.url
                Layout.fillWidth: true; wrapMode: Text.WordWrap
            }
            RowLayout {
                Layout.alignment: Qt.AlignRight
                FlatButton { objectName: "copySongLink"; text: linkDialog.copied ? "Copied" : "Copy"; enabled: !!linkDialog.url; onClicked: linkDialog.copied = app.copyLink(root.selectedTrack.id) }
                FlatButton { text: "Open"; glyph: "external-link"; enabled: !!linkDialog.url; onClicked: app.openLink(root.selectedTrack.id) }
            }
        }
    }
    AppDialog {
        id: trashDialog
        objectName: "trashSongDialog"
        anchors.centerIn: parent
        width: Math.min(root.width - 40, 450)
        title: "Move song to Trash?"
        standardButtons: Dialog.Cancel
        ColumnLayout {
            width: parent.width; spacing: 14
            Label { text: root.selectedTrack.title || ""; color: Theme.text; wrapMode: Text.WordWrap; Layout.fillWidth: true }
            Label { text: "The audio file stays in the system Trash until you empty it. Restore it there to recover it."; color: Theme.muted; wrapMode: Text.WordWrap; Layout.fillWidth: true }
            FlatButton {
                objectName: "confirmTrashSong"; text: "Move to Trash"; glyph: "trash-2"
                Layout.alignment: Qt.AlignRight
                onClicked: { app.trashTrack(root.selectedTrack.id); trashDialog.close(); if (!app.current.id) root.expanded = false; }
            }
        }
    }
    AppDialog {
        id: errorDialog
        anchors.centerIn: parent
        width: Math.min(root.width - 60, 540)
        title: "Could not complete that action"
        standardButtons: Dialog.Ok
        Label {
            id: errorLabel
            width: parent.width
            wrapMode: Text.WordWrap
            color: Theme.text
        }
    }
    AppDialog {
        id: nameDialog
        anchors.centerIn: parent
        width: 360
        title: root.renameMode ? "Rename collection" : root.collectionKind === "custom" ? "New custom album" : "New playlist"
        standardButtons: Dialog.Save | Dialog.Cancel
        AppField {
            id: nameInput
            width: parent.width
            placeholderText: "Name"
            selectByMouse: true
        }
        onAccepted: {
            if (root.renameMode) app.renameCollection(root.selectedCollection.id, nameInput.text);
            else app.createCollection(nameInput.text, root.collectionKind);
        }
        onOpened: {
            nameInput.forceActiveFocus();
            nameDialog.standardButton(Dialog.Save).enabled = Qt.binding(() => nameInput.text.trim().length > 0);
        }
    }
    AppDialog {
        id: deleteDialog
        anchors.centerIn: parent
        title: "Delete collection?"
        standardButtons: Dialog.Yes | Dialog.No
        Label {
            text: "Audio files will be kept."
            color: Theme.text
        }
        onAccepted: {
            // back out if the user deleted the playlist theyre currently looking at
            const deletedId = root.selectedCollection.id;
            app.deleteCollection(deletedId);
            if (musicPane.collectionId === deletedId) {
                musicPane.collectionId = "";
                musicPane.collectionName = "";
            }
        }
    }
    function insertTrack(collection, again = false) {
        const result = app.addTracksToCollection(collection, [root.selectedTrack.id], again ? "duplicates" : "auto");
        if (result.status === "done") {
            addDialog.close();
            if (result.added > 0) musicPane.resultMessage = "Added to playlist";
            else if (result.skipped > 0) musicPane.resultMessage = "Already in playlist (skipped)";
        } else if (result.message) {
            errorLabel.text = result.message; errorDialog.open();
        }
    }
    function addSongs(collection) { addSongsDialog.selectedIds = ({}); addSongsDialog.anchorIndex = -1; addSongsDialog.feedback = ""; addSongsCollection = collection; addSongSearch.text = ""; addSongsDialog.open(); }
    AppDialog {
        id: detailsDialog
        anchors.centerIn: parent; width: Math.min(root.width - 40, 600)
        title: "Download details"; standardButtons: Dialog.Close
        ScrollView {
            width: parent.width; height: Math.min(300, root.height - 200); clip: true; contentWidth: availableWidth
            Label { id: technicalDetails; width: parent.width; wrapMode: Text.WrapAnywhere; color: Theme.text; font.pixelSize: 12 }
        }
    }
    AppDialog {
        id: addDialog
        objectName: "addToPlaylistDialog"
        anchors.centerIn: parent; width: Math.min(root.width - 40, 460)
        title: "Add to Playlist"; standardButtons: Dialog.Close
        property string pendingCreateName: ""
        property bool enteringCustomName: false
        onOpened: {
            playlistSearch.text = "";
            pendingCreateName = "";
            enteringCustomName = false;
            inlinePlaylistName.text = "";
        }
        ColumnLayout {
            width: parent.width; spacing: 10
            Label { text: root.selectedTrack.title || ""; color: Theme.muted; elide: Text.ElideRight; Layout.fillWidth: true }
            AppField {
                id: playlistSearch; search: true; placeholderText: "Find or name a playlist"; Layout.fillWidth: true
                onTextChanged: if (addDialog.pendingCreateName) addDialog.pendingCreateName = ""
            }
            CollectionRow {
                Layout.fillWidth: true
                visible: !addDialog.pendingCreateName && !addDialog.enteringCustomName
                title: playlistSearch.text.trim() ? 'Create playlist "' + playlistSearch.text.trim() + '"' : "Create playlist"
                subtitle: playlistSearch.text.trim() ? "Use this name & add song" : "Type a name above or tap to enter"
                glyph: "plus"
                onClicked: {
                    const typed = playlistSearch.text.trim();
                    if (typed.length > 0) addDialog.pendingCreateName = typed;
                    else { inlinePlaylistName.text = ""; addDialog.enteringCustomName = true; inlinePlaylistName.forceActiveFocus(); }
                }
            }
            Rectangle {
                Layout.fillWidth: true
                visible: !!addDialog.pendingCreateName
                implicitHeight: inlineConfirmCol.implicitHeight + 24
                radius: 10; color: Qt.alpha(Theme.accent, 0.08); border.width: 1; border.color: Qt.alpha(Theme.accent, 0.35)
                ColumnLayout {
                    id: inlineConfirmCol
                    anchors.fill: parent; anchors.margins: 12; spacing: 10
                    Label {
                        text: 'Create playlist "' + addDialog.pendingCreateName + '" and add this song?'
                        color: Theme.text; wrapMode: Text.WordWrap; Layout.fillWidth: true; font.weight: Font.Medium
                    }
                    RowLayout {
                        Layout.alignment: Qt.AlignRight; spacing: 8
                        FlatButton { text: "Edit name"; onClicked: { inlinePlaylistName.text = addDialog.pendingCreateName; addDialog.pendingCreateName = ""; addDialog.enteringCustomName = true; inlinePlaylistName.forceActiveFocus(); } }
                        FlatButton { text: "Cancel"; onClicked: addDialog.pendingCreateName = "" }
                        FlatButton {
                            text: "Create & add"; glyph: "check"; primary: true
                            onClicked: {
                                const id = app.createCollection(addDialog.pendingCreateName, "playlist");
                                addDialog.pendingCreateName = "";
                                if (id) root.insertTrack(id);
                            }
                        }
                    }
                }
            }
            Rectangle {
                Layout.fillWidth: true
                visible: addDialog.enteringCustomName
                implicitHeight: inlineCreateCol.implicitHeight + 24
                radius: 10; color: Theme.surface; border.width: 1; border.color: Theme.separator
                ColumnLayout {
                    id: inlineCreateCol
                    anchors.fill: parent; anchors.margins: 12; spacing: 10
                    AppField {
                        id: inlinePlaylistName; placeholderText: "New playlist name"; Layout.fillWidth: true
                        onAccepted: if (text.trim().length > 0) {
                            const id = app.createCollection(text.trim(), "playlist");
                            addDialog.enteringCustomName = false;
                            if (id) root.insertTrack(id);
                        }
                    }
                    RowLayout {
                        Layout.alignment: Qt.AlignRight; spacing: 8
                        FlatButton { text: "Cancel"; onClicked: addDialog.enteringCustomName = false }
                        FlatButton {
                            text: "Create & add"; glyph: "check"; primary: true
                            enabled: inlinePlaylistName.text.trim().length > 0
                            onClicked: {
                                const id = app.createCollection(inlinePlaylistName.text.trim(), "playlist");
                                addDialog.enteringCustomName = false;
                                if (id) root.insertTrack(id);
                            }
                        }
                    }
                }
            }
            ListView {
                Layout.fillWidth: true; Layout.preferredHeight: Math.min(300, root.height - 380)
                clip: true
                model: {
                    if (!addDialog.visible) return [];
                    const editable = app.collections.filter(c => c.kind === "playlist" || c.kind === "custom");
                    const pinned = editable.filter(c => !!c.pinned);
                    const rest = editable.filter(c => !c.pinned);
                    return pinned.concat([{id:"liked", name:"Liked Songs", kind:"liked", count:library.likedCount}], rest)
                        .filter(c => c.name.toLowerCase().includes(playlistSearch.text.toLowerCase()));
                }
                ScrollBar.vertical: ScrollBar {}
                delegate: CollectionRow {
                    required property var modelData
                    width: ListView.view.width
                    title: modelData.name; cover: modelData.cover || ""; glyph: modelData.kind === "liked" ? "heart" : ""
                    pinned: !!modelData.pinned
                    subtitle: (modelData.count || 0) + " songs" + (app.collectionContains(modelData.id, root.selectedTrack.id) ? " · Already added" : "")
                    onClicked: root.insertTrack(modelData.id)
                }
                Label { anchors.centerIn: parent; text: "No matching playlists"; visible: parent.count === 0; color: Theme.muted }
            }
        }
    }
    AppDialog {
        id: addSongsDialog
        objectName: "addSongsDialog"
        anchors.centerIn: parent; width: Math.min(root.width - 40, 540)
        title: "Add songs"; standardButtons: Dialog.Cancel
        property var selectedIds: ({})
        property int anchorIndex: -1
        property string feedback: ""
        readonly property var filteredTracks: visible ? library.tracks.filter(t => ((t.title || "") + " " + (t.artist || "")).toLowerCase().includes(addSongSearch.text.toLowerCase())) : []
        function toggle(index, modifiers) {
            if (index < 0 || index >= filteredTracks.length) return;
            let ids = Object.assign({}, selectedIds);
            if ((modifiers & Qt.ShiftModifier) && anchorIndex >= 0) {
                for (let i = Math.min(index, anchorIndex); i <= Math.max(index, anchorIndex); ++i) ids[filteredTracks[i].id] = true;
            } else {
                const id = filteredTracks[index].id;
                if (ids[id]) delete ids[id]; else ids[id] = true;
                anchorIndex = index;
            }
            selectedIds = ids;
        }
        ColumnLayout {
            width: parent.width; spacing: 10
            AppField { id: addSongSearch; objectName: "addSongSearch"; search: true; placeholderText: "Search your library"; Layout.fillWidth: true; onTextChanged: addSongsDialog.anchorIndex = -1 }
            ListView {
                id: songChoices
                Layout.fillWidth: true; Layout.preferredHeight: Math.max(80, Math.min(360, root.height - 330)); clip: true
                model: addSongsDialog.filteredTracks
                ScrollBar.vertical: ScrollBar {}
                Keys.onPressed: event => {
                    if (event.key === Qt.Key_Space && currentIndex >= 0) {
                        addSongsDialog.toggle(currentIndex, event.modifiers); event.accepted = true;
                    }
                    if (event.key === Qt.Key_A && (event.modifiers & Qt.ControlModifier)) {
                        let ids = Object.assign({}, addSongsDialog.selectedIds);
                        addSongsDialog.filteredTracks.forEach(t => ids[t.id] = true);
                        addSongsDialog.selectedIds = ids; event.accepted = true;
                    }
                }
                delegate: CollectionRow {
                    required property var modelData
                    required property int index
                    width: ListView.view.width; title: modelData.title; cover: modelData.art || ""
                    selectable: true; selected: !!addSongsDialog.selectedIds[modelData.id]
                    subtitle: modelData.artist || "Unknown artist"
                    onClicked: { songChoices.forceActiveFocus(); addSongsDialog.toggle(index, 0); }
                    MouseArea {
                        anchors.fill: parent
                        onClicked: mouse => { songChoices.forceActiveFocus(); addSongsDialog.toggle(index, mouse.modifiers); }
                    }
                }
                Label { anchors.centerIn: parent; text: library.tracks.length ? "No matching songs" : "Your library is empty"; visible: parent.count === 0; color: Theme.muted }
            }
            Label { text: Object.keys(addSongsDialog.selectedIds).length + " songs selected"; color: Theme.muted }
            Label { text: addSongsDialog.feedback; visible: !!text; color: Theme.muted; Layout.fillWidth: true; wrapMode: Text.Wrap }
            FlatButton {
                objectName: "confirmAddSongs"; text: "Add selected"; glyph: "plus"; primary: true; Layout.alignment: Qt.AlignRight
                enabled: Object.keys(addSongsDialog.selectedIds).length > 0
                onClicked: {
                    const ids = library.tracks.filter(t => !!addSongsDialog.selectedIds[t.id]).map(t => t.id);
                    const result = app.addTracksToCollection(root.addSongsCollection, ids, "auto");
                    addSongsDialog.feedback = result.status === "error" ? result.message : result.added + " added · " + result.skipped + " skipped";
                    if (result.status === "done") addSongsDialog.selectedIds = ({});
                }
            }
        }
    }

    AppDialog {
        id: metadataDialog
        objectName: "metadataDialog"
        anchors.centerIn: parent
        width: Math.min(root.width - 60, 440)
        title: "Edit embedded metadata"
        standardButtons: Dialog.Save | Dialog.Cancel
        property string coverPath: ""
        ColumnLayout {
            width: parent.width
            spacing: 8
            Label {
                text: "Title"
                color: Theme.muted
            }
            RowLayout {
                Layout.fillWidth: true
                spacing: 6
                AppField {
                    id: titleInput
                    objectName: "metadataTitle"
                    Layout.fillWidth: true
                }
                FlatButton {
                    text: "Clean"
                    hint: "Strip (Official Audio), (Official Video), [Lyrics], etc."
                    enabled: app.cleanTitle(titleInput.text) !== titleInput.text
                    onClicked: titleInput.text = app.cleanTitle(titleInput.text)
                }
            }
            Label {
                text: "Artist / Creator"
                color: Theme.muted
            }
            AppField {
                id: artistInput
                objectName: "metadataArtist"
                Layout.fillWidth: true
            }
            Label {
                text: "Album"
                color: Theme.muted
            }
            AppField {
                id: albumInput
                objectName: "metadataAlbum"
                Layout.fillWidth: true
            }
            Label {
                text: "Album artist"
                color: Theme.muted
            }
            AppField {
                id: albumArtistInput
                objectName: "metadataAlbumArtist"
                Layout.fillWidth: true
            }
            FlatButton {
                text: metadataDialog.coverPath ? "Artwork selected" : "Change artwork"
                glyph: "image"
                onClicked: {
                    let path = app.chooseImage();
                    if (path)
                        metadataDialog.coverPath = path;
                }
            }
            FlatButton {
                objectName: "findMetadata"
                text: "Find tags on MusicBrainz"
                glyph: "search"
                onClicked: {
                    catalogueTitle.text = titleInput.text;
                    catalogueArtist.text = artistInput.text === "Unknown artist" ? "" : artistInput.text;
                    catalogueDialog.open();
                    catalogue.search(catalogueTitle.text, catalogueArtist.text);
                }
            }
        }
        onClosed: {
            catalogueDialog.close();
            catalogue.cancel();
        }
        onAccepted: library.editTags(root.selectedTrack.id, {
                "title": titleInput.text,
                "artist": artistInput.text,
                "album": albumInput.text,
                "albumArtist": albumArtistInput.text,
                "cover": coverPath
            })
    }
    AppDialog {
        id: catalogueDialog
        objectName: "catalogueDialog"
        anchors.centerIn: parent
        width: Math.min(root.width - 60, 660)
        title: "Find tags · MusicBrainz"
        standardButtons: Dialog.Close
        onClosed: catalogue.cancel()
        ColumnLayout {
            width: parent.width
            spacing: 10
            AppField {
                id: catalogueTitle
                objectName: "catalogueTitle"
                Layout.fillWidth: true
                placeholderText: "Song title"
                onAccepted: catalogue.search(text, catalogueArtist.text)
            }
            RowLayout {
                Layout.fillWidth: true
                AppField {
                    id: catalogueArtist
                    objectName: "catalogueArtist"
                    Layout.fillWidth: true
                    placeholderText: "Artist (optional)"
                    onAccepted: catalogue.search(catalogueTitle.text, text)
                }
                FlatButton {
                    objectName: "catalogueSearch"
                    text: "Search"
                    glyph: "search"
                    onClicked: catalogue.search(catalogueTitle.text, catalogueArtist.text)
                }
            }
            RowLayout {
                Layout.fillWidth: true
                AppBusy { running: catalogue.busy; visible: running; implicitWidth: 20; implicitHeight: 20 }
                Label {
                    text: catalogue.status
                    color: Theme.muted
                    Layout.fillWidth: true
                    wrapMode: Text.Wrap
                    font.pixelSize: 12
                }
            }
            ListView {
                Layout.fillWidth: true
                Layout.preferredHeight: Math.min(280, root.height - 300)
                clip: true
                model: catalogue.results
                ScrollBar.vertical: ScrollBar {}
                delegate: ItemDelegate {
                    id: match
                    objectName: "catalogueMatch"
                    required property var modelData
                    width: ListView.view.width
                    height: 78
                    padding: 10
                    background: Rectangle { color: match.hovered ? Theme.hover : "transparent"; radius: 6 }
                    contentItem: Column {
                        spacing: 3
                        Label { width: parent.width; text: match.modelData.title + (match.modelData.version ? " · " + match.modelData.version : ""); color: Theme.text; elide: Text.ElideRight }
                        Label { width: parent.width; text: match.modelData.artist; color: Theme.muted; elide: Text.ElideRight; font.pixelSize: 12 }
                        Label { width: parent.width; text: (match.modelData.album || "Album not specified") + (match.modelData.duration > 0 ? " · " + app.time(match.modelData.duration) : ""); color: Theme.muted; elide: Text.ElideRight; font.pixelSize: 12 }
                    }
                    onClicked: {
                        titleInput.text = modelData.title;
                        artistInput.text = modelData.artist;
                        albumInput.text = modelData.album;
                        albumArtistInput.text = modelData.albumArtist;
                        catalogueDialog.close();
                    }
                }
            }
            Label {
                text: "Review the fields, then Save to write tags."
                color: Theme.muted
                font.pixelSize: 12
            }
        }
    }
    AppDialog {
        id: infoDialog
        anchors.centerIn: parent
        width: Math.min(root.width - 60, 560)
        title: "Track information"
        standardButtons: Dialog.Close
        ColumnLayout {
            width: parent.width
            spacing: 10
            Repeater {
                model: [{
                        "label": "File",
                        "key": "path"
                    }, {
                        "label": "Album",
                        "key": "album"
                    }, {
                        "label": "Artist / Creator",
                        "key": "artist"
                    }, {
                        "label": "Source uploader",
                        "key": "uploader"
                    }, {
                        "label": "Original source title",
                        "key": "videoTitle"
                    }, {
                        "label": "Source URL",
                        "key": "sourceUrl"
                    }]
                Label {
                    required property var modelData
                    text: modelData.label + ": " + (root.selectedTrack[modelData.key] || "—")
                    Layout.fillWidth: true
                    wrapMode: Text.WrapAnywhere
                    color: Theme.text
                }
            }
        }
    }
    SongVolumeDialog { id: songVolumeDialog }
    AppDialog {
        id: speedDialog
        objectName: "speedDialog"
        anchors.centerIn: parent
        width: Math.min(root.width - 40, 400)
        property string trackId: ""
        property bool dirty: false
        function commit() {
            speedPreview.stop();
            if (!dirty) return;
            dirty = false;
            app.applyTrackSpeed(trackId, rate.value, nightcore.checked);
        }
        Timer {
            id: speedPreview
            interval: 80
            onTriggered: app.previewTrackSpeed(speedDialog.trackId, rate.value, nightcore.checked)
        }
        onClosed: commit()
        title: "Song playback speed"
        standardButtons: Dialog.Close
        ColumnLayout {
            width: parent.width
            spacing: 12
            Label {
                text: "Saved for this song only"; color: Theme.muted; font.pixelSize: 12
                Layout.fillWidth: true
            }
            GridLayout {
                columns: 3
                Layout.fillWidth: true
                Repeater {
                    model: [0.5, 0.75, 1, 1.25, 1.5, 2]
                    FlatButton {
                        required property real modelData
                        Layout.fillWidth: true
                        Layout.minimumWidth: 0
                        checked: Math.abs(modelData - rate.value) < 0.005
                        objectName: "speedPreset"
                        text: modelData.toFixed(2) + "×"
                        onClicked: {
                            rate.value = modelData;
                            speedDialog.dirty = true;
                            speedDialog.commit();
                        }
                    }
                }
            }
            AppSlider {
                id: rate
                objectName: "songSpeedSlider"
                decimals: 2
                suffix: "×"
                from: 0.5
                to: 2
                stepSize: 0.01
                Layout.fillWidth: true
                value: 1.0
                onMoved: {
                    speedDialog.dirty = true;
                    if (pressed) { if (!speedPreview.running) speedPreview.start(); }
                    else speedDialog.commit();
                }
                onPressedChanged: if (!pressed) speedDialog.commit()
            }
            AppField {
                text: rate.value.toFixed(2)
                validator: DoubleValidator {
                    bottom: 0.5
                    top: 2
                    decimals: 2
                    locale: "C"
                }
                onEditingFinished: {
                    const n = Number(text);
                    if (n >= 0.5 && n <= 2) {
                        rate.value = n;
                        speedDialog.dirty = true;
                        speedDialog.commit();
                    } else
                        text = rate.value.toFixed(2);
                }
                Layout.preferredWidth: 90
                Accessible.name: "Playback speed"
            }
            AppCheck {
                id: nightcore
                text: (rate.value < 1 ? "Daycore" : "Nightcore") + " · pitch follows speed"
                checked: false
                onToggled: { speedDialog.dirty = true; speedDialog.commit(); }
            }
        }
    }
    AppDialog {
        id: eqDialog
        objectName: "eqDialog"
        anchors.centerIn: parent
        width: Math.min(root.width - 50, 620)
        title: "Equalizer"
        standardButtons: Dialog.Close
        property var gains: app.settings.eqGains
        property bool savingPreset: false
        property string presetError: ""
        readonly property var builtinPresets: [
            { name: "Flat", gains: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0], custom: false },
            { name: "Bass Boost", gains: [6, 5, 4, 2, 0, 0, 0, 0, 0, 0], custom: false },
            { name: "Rock", gains: [4, 3, 1, -1, -2, 1, 3, 4, 4, 3], custom: false },
            { name: "Pop", gains: [-1, 1, 3, 4, 3, 0, -1, -1, 1, 2], custom: false },
            { name: "Classical", gains: [3, 2, 0, 0, -1, -1, 0, 2, 3, 4], custom: false }
        ]
        readonly property var customPresets: app.settings.eqCustomPresets || []
        readonly property var presetNames: builtinPresets.map(p => p.name).concat(customPresets.map(p => p.name), ["Custom"])
        readonly property var activeCustomPreset: customPresets.find(p => p.name === eqPreset.currentText) || null
        function syncCombo() {
            const idx = presetNames.indexOf(app.settings.eqPreset || "Flat");
            eqPreset.currentIndex = idx >= 0 ? idx : presetNames.length - 1;
        }
        onAboutToShow: {
            gains = (app.settings.eqGains || [0,0,0,0,0,0,0,0,0,0]).slice();
            savingPreset = false;
            presetError = "";
            syncCombo();
        }
        onPresetNamesChanged: if (opened) syncCombo()
        Connections {
            target: app
            function onSettingsChanged() {
                if (eqDialog.opened && !eqDialog.savingPreset) {
                    eqDialog.gains = (app.settings.eqGains || [0,0,0,0,0,0,0,0,0,0]).slice();
                    eqDialog.syncCombo();
                }
            }
        }
        function apply(values, presetName) {
            gains = values;
            app.applyEq(eqEnabled.checked, values, presetName || "Custom");
            syncCombo();
        }
        ColumnLayout {
            width: parent.width
            spacing: 14
            RowLayout {
                Layout.fillWidth: true
                spacing: 8
                AppCheck {
                    id: eqEnabled
                    text: "Enabled"
                    checked: app.settings.eqEnabled
                    onToggled: app.applyEq(checked, eqDialog.gains, eqPreset.currentText)
                }
                AppCombo {
                    id: eqPreset
                    objectName: "eqPresetCombo"
                    model: eqDialog.presetNames
                    Layout.minimumWidth: 160
                    onActivated: {
                        const chosen = currentText;
                        const builtin = eqDialog.builtinPresets.find(p => p.name === chosen);
                        if (builtin) { eqDialog.apply(builtin.gains.slice(), chosen); return; }
                        const saved = eqDialog.customPresets.find(p => p.name === chosen);
                        if (saved) eqDialog.apply((saved.gains || []).slice(), saved.name);
                    }
                }
                Item {
                    Layout.fillWidth: true
                }
                FlatButton {
                    objectName: "saveEqPresetButton"
                    text: eqDialog.activeCustomPreset ? "Update preset" : "Save preset"
                    glyph: "plus"
                    outlined: true
                    onClicked: {
                        eqPresetName.text = eqDialog.activeCustomPreset ? eqDialog.activeCustomPreset.name : "";
                        eqDialog.presetError = "";
                        eqDialog.savingPreset = true;
                        eqPresetName.forceActiveFocus();
                    }
                }
                FlatButton {
                    objectName: "deleteEqPresetButton"
                    visible: !!eqDialog.activeCustomPreset
                    glyph: "trash-2"
                    hint: "Delete saved preset"
                    danger: true
                    onClicked: {
                        if (eqDialog.activeCustomPreset) app.deleteEqPreset(eqDialog.activeCustomPreset.id);
                        eqDialog.syncCombo();
                    }
                }
                FlatButton {
                    text: "Reset"
                    onClicked: {
                        eqPreset.currentIndex = 0;
                        eqDialog.apply([0, 0, 0, 0, 0, 0, 0, 0, 0, 0], "Flat");
                    }
                }
            }
            Rectangle {
                Layout.fillWidth: true
                visible: eqDialog.savingPreset
                implicitHeight: savePresetCol.implicitHeight + 20
                radius: 8; color: Theme.surface; border.width: 1; border.color: Theme.separator
                ColumnLayout {
                    id: savePresetCol
                    anchors.fill: parent; anchors.margins: 10; spacing: 8
                    RowLayout {
                        Layout.fillWidth: true; spacing: 8
                        AppField {
                            id: eqPresetName
                            objectName: "eqPresetNameField"
                            Layout.fillWidth: true
                            placeholderText: "Preset name (syncs with phone)"
                            onAccepted: {
                                if (text.trim().length > 0) {
                                    const res = app.saveEqPreset(text.trim(), eqDialog.gains);
                                    if (res.ok) { eqDialog.savingPreset = false; eqDialog.presetError = ""; eqDialog.syncCombo(); }
                                    else eqDialog.presetError = res.error || "Could not save preset";
                                }
                            }
                        }
                        FlatButton {
                            text: "Cancel"
                            onClicked: { eqDialog.savingPreset = false; eqDialog.presetError = ""; }
                        }
                        FlatButton {
                            objectName: "confirmSaveEqPreset"
                            text: "Save"; glyph: "check"; primary: true
                            enabled: eqPresetName.text.trim().length > 0
                            onClicked: {
                                const res = app.saveEqPreset(eqPresetName.text.trim(), eqDialog.gains);
                                if (res.ok) { eqDialog.savingPreset = false; eqDialog.presetError = ""; eqDialog.syncCombo(); }
                                else eqDialog.presetError = res.error || "Could not save preset";
                            }
                        }
                    }
                    Label { visible: !!eqDialog.presetError; text: eqDialog.presetError; color: Theme.danger; font.pixelSize: 12 }
                }
            }
            RowLayout {
                Layout.fillWidth: true
                Repeater {
                    model: ["31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"]
                    ColumnLayout {
                        required property string modelData
                        required property int index
                        Layout.fillWidth: true
                        AppSlider {
                            orientation: Qt.Vertical
                            from: -12
                            to: 12
                            stepSize: 1
                            value: eqDialog.gains[index] || 0
                            Layout.preferredHeight: 160
                            Layout.alignment: Qt.AlignHCenter
                            onMoved: {
                                let g = eqDialog.gains.slice();
                                g[index] = value;
                                eqPreset.currentIndex = eqDialog.presetNames.length - 1;
                                eqDialog.apply(g);
                            }
                            Accessible.name: modelData + " Hz gain"
                        }
                        Label {
                            text: modelData
                            color: Theme.muted
                            font.pixelSize: 10
                            Layout.alignment: Qt.AlignHCenter
                        }
                    }
                }
            }
        }
    }
    AppDialog {
        id: downloadDialog
        objectName: "downloadDialog"
        anchors.centerIn: parent
        width: Math.min(root.width - 60, 510)
        title: "Download"
        standardButtons: Dialog.Cancel
        ColumnLayout {
            width: parent.width
            spacing: 12
            Label {
                text: root.downloadDetails.title || ""
                color: Theme.text
                Layout.fillWidth: true
                elide: Text.ElideRight
            }
            AppCombo {
                id: formatBox
                visible: root.downloadDetails.provider !== "direct"
                objectName: "downloadFormat"
                Layout.fillWidth: true
                model: ["Original audio", "MP3"]
            }
            Label {
                readonly property string streamQuality: online.streamQualityLabel(root.downloadDetails)
                text: {
                    if (root.downloadDetails.provider === "direct")
                        return "Original file · existing audio and tags are preserved";
                    const base = formatBox.currentIndex === 0
                        ? "Best source audio · no extra lossy encoding"
                        : "Best source audio · high-quality MP3 conversion";
                    return streamQuality ? ("Source stream: " + streamQuality + "\n" + base) : base;
                }
                color: Theme.muted; font.pixelSize: 12; wrapMode: Text.Wrap; Layout.fillWidth: true
            }
            RowLayout {
                Layout.fillWidth: true
                AppField {
                    id: destination
                    Layout.fillWidth: true
                    placeholderText: "Destination folder"
                }
                FlatButton {
                    glyph: "folder"
                    hint: "Choose destination"
                    onClicked: {
                        let p = app.chooseFolder(destination.text);
                        if (p)
                            destination.text = p;
                    }
                }
            }
        }
        footer: Item {
            implicitHeight: 64
            RowLayout {
                anchors.right: parent.right
                anchors.rightMargin: 24
                anchors.top: parent.top
                anchors.topMargin: 8
                spacing: 8
                FlatButton {
                    text: "Cancel"
                    onClicked: downloadDialog.close()
                }
                FlatButton {
                    objectName: "startDownload"
                    text: "Start download"
                    glyph: "download"
                    primary: true
                    onClicked: root.startDownload(false)
                }
            }
        }
    }
    AppDialog {
        id: duplicateDialog
        anchors.centerIn: parent
        title: "Already downloaded"
        standardButtons: Dialog.Cancel
        ColumnLayout {
            Label {
                text: "A matching completed file already exists."
                color: Theme.text
            }
            RowLayout {
                FlatButton {
                    text: "Open"
                    onClicked: {
                        app.openFile(root.duplicatePath);
                        duplicateDialog.close();
                        downloadDialog.close();
                    }
                }
                FlatButton {
                    text: "Skip"
                    onClicked: {
                        duplicateDialog.close();
                        downloadDialog.close();
                    }
                }
                FlatButton {
                    text: "Download again"
                    onClicked: {
                        duplicateDialog.close();
                        root.startDownload(true);
                    }
                }
            }
        }
    }
}
