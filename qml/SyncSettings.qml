import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

ColumnLayout {
    id: syncSettings
    Layout.fillWidth: true
    spacing: 10
    property var conflictRows: app.sync.conflicts
    property string bulkPeer: ""
    property var bulkSnapshot: []
    readonly property var songConflictCounts: {
        var counts = {};
        for (var i = 0; i < conflictRows.length; ++i) {
            var row = conflictRows[i];
            if (row.entity === "tracks") counts[row.peer] = (counts[row.peer] || 0) + 1;
        }
        return counts;
    }
    function chooseAll(row, checked) {
        if (!checked) { bulkPeer = ""; bulkSnapshot = []; return; }
        bulkPeer = row.peer;
        bulkSnapshot = conflictRows.filter(function(item) {
            return item.peer === row.peer && item.entity === "tracks";
        }).map(function(item) {
            return {id: item.id, peer: item.peer, entity: item.entity, revision: item.revision, remote: item.remote};
        });
    }
    function appliesToAll(row) {
        return row.entity === "tracks" && bulkPeer === row.peer && bulkSnapshot.length > 0;
    }
    function choose(row, usePhone) {
        if (appliesToAll(row)) {
            var snapshot = bulkSnapshot;
            bulkPeer = ""; bulkSnapshot = [];
            app.sync.resolveMany(snapshot, usePhone);
        } else app.sync.resolve(row.id, usePhone, row.revision, row.remote);
    }
    function details(row) {
        try {
            var desktop = JSON.parse(row.local), phone = JSON.parse(row.remote), fields = JSON.parse(row.fields)
            var labels = {entries: "Playlist order", volumePercent: "Song volume", audioHash: "Audio file", artHash: "Artwork", position: "Playlist order", deletion: "Removal", title: "Title", artist: "Artist", album: "Album", speed: "Playback speed", nightcore: "Nightcore/Daycore"}
            return fields.map(function(field) {
                function value(record) { if (field === "entries") return (record === desktop ? row.localOrder : row.remoteOrder).join(" → "); if (!record) return "Removed"; if (field === "audioHash" || field === "artHash") return "Changed"; return record[field] === undefined ? "Unchanged" : String(record[field]) }
                return (labels[field] || field) + "\nDesktop: " + value(desktop) + "\nPhone: " + value(phone)
            }).join("\n\n")
        } catch (error) { return "Review this library change on either device." }
    }
    function title(row) {
        if (row.label) return "Review: " + row.label
        try { var value = JSON.parse(row.local) || JSON.parse(row.remote); return "Review: " + (value.title || value.name || (row.entity === "members" ? "Playlist entry" : "Library change")) }
        catch (error) { return "Review library change" }
    }
    function consequence(row, phone) {
        try {
            var value = JSON.parse(phone ? row.remote : row.local)
            if (!value) return "Remove this song from the synced libraries. Audio files stay on disk."
            return "Use the " + (phone ? "phone" : "desktop") + " song metadata and audio reference on both devices. Changed audio transfers after the choice reaches the phone."
        } catch (error) { return "Apply this version on the paired devices." }
    }
    Label { text: "Phone sync"; color: Theme.text; font.pixelSize: 20; font.weight: Font.DemiBold }
    Rectangle {
        Layout.fillWidth: true
        implicitHeight: syncMainCard.implicitHeight + 36
        radius: 10
        color: Theme.surface
        border.width: 1
        border.color: Theme.separator
        ColumnLayout {
            id: syncMainCard
            anchors.fill: parent
            anchors.margins: 18
            spacing: 12
            RowLayout {
                Layout.fillWidth: true
                spacing: 10
                AppCheck { text: "Enable local Wi-Fi sync"; checked: app.sync.enabled; onToggled: app.sync.setEnabled(checked); Layout.fillWidth: true }
                Label { text: app.sync.status; color: app.sync.enabled ? Theme.accent : Theme.muted; font.pixelSize: 12 }
            }
            RowLayout {
                Layout.fillWidth: true
                spacing: 10
                visible: app.sync.enabled
                AppCombo { id: address; model: app.sync.addresses; Layout.fillWidth: true }
                FlatButton { text: "Pair phone"; glyph: "plus"; primary: true; onClicked: app.sync.pair(address.currentText) }
                FlatButton { text: "Prepare library"; outlined: true; enabled: !app.sync.busy; onClicked: app.sync.prepare() }
            }
            AppProgress {
                visible: app.sync.busy
                Layout.fillWidth: true
                indeterminate: app.sync.progress < 0
                value: Math.max(0, app.sync.progress)
            }
            Label { visible: app.sync.enabled; text: "Pairing grants access to this library. Start a sync from the phone to connect. While its app stays open, shared choices update on both devices. Resolve a change here or on the phone; unresolved audio stays untouched."; color: Theme.muted; font.pixelSize: 12; wrapMode: Text.WordWrap; Layout.fillWidth: true }
            Image { visible: !!app.sync.pairingQr; source: app.sync.pairingQr; sourceSize: Qt.size(300,300); Layout.preferredWidth: 300; Layout.preferredHeight: visible ? 300 : 0; fillMode: Image.PreserveAspectFit; smooth: false }
            FlatButton { visible: !!app.sync.pairingLink; text: "Copy pairing link · expires in 5 minutes"; glyph: "external-link"; outlined: true; onClicked: app.sync.copyLink() }
        }
    }
    Rectangle {
        visible: app.sync.devices.length > 0
        Layout.fillWidth: true
        implicitHeight: devicesCard.implicitHeight + 36
        radius: 10
        color: Theme.surface
        border.width: 1
        border.color: Theme.separator
        ColumnLayout {
            id: devicesCard
            anchors.fill: parent
            anchors.margins: 18
            spacing: 10
            Label { text: "Paired devices"; color: Theme.text; font.pixelSize: 15; font.weight: Font.Medium }
            Repeater {
                model: app.sync.devices
                RowLayout {
                    required property var modelData
                    Layout.fillWidth: true
                    spacing: 8
                    Label { text: modelData.name; color: Theme.text; Layout.fillWidth: true; elide: Text.ElideRight }
                    FlatButton { text: "Pair again"; outlined: true; enabled: app.sync.enabled; onClicked: app.sync.pair(address.currentText, modelData.id) }
                    FlatButton { text: "Unpair"; glyph: "x"; outlined: true; danger: true; onClicked: app.sync.revoke(modelData.id) }
                }
            }
        }
    }
    Label {
        text: app.sync.resolutionSummary.message || ""
        visible: text.length > 0
        color: Theme.muted
        wrapMode: Text.WordWrap
        Layout.fillWidth: true
    }
    readonly property var songConflictGroups: {
        var groups = {};
        for (var i = 0; i < conflictRows.length; ++i) {
            var row = conflictRows[i];
            if (row.entity !== "tracks") continue;
            if (!groups[row.peer]) groups[row.peer] = [];
            groups[row.peer].push({id: row.id, peer: row.peer, entity: row.entity, revision: row.revision, remote: row.remote});
        }
        return Object.keys(groups).map(function(peer) { return {peer: peer, items: groups[peer]}; }).filter(function(g) { return g.items.length > 1; });
    }
    function fieldDiffs(row) {
        try {
            var desktop = JSON.parse(row.local), phone = JSON.parse(row.remote), fields = JSON.parse(row.fields);
            var labels = {entries: "Playlist order", volumePercent: "Song volume", audioHash: "Audio file", artHash: "Artwork", position: "Playlist order", deletion: "Removal", title: "Title", artist: "Artist", album: "Album", speed: "Playback speed", nightcore: "Nightcore/Daycore"};
            return fields.map(function(field) {
                function value(record) {
                    if (field === "entries") return (record === desktop ? row.localOrder : row.remoteOrder).join(" → ");
                    if (!record) return "Removed";
                    if (field === "audioHash" || field === "artHash") return "Changed";
                    return record[field] === undefined ? "Unchanged" : String(record[field]);
                }
                return {label: labels[field] || field, desktop: value(desktop), phone: value(phone)};
            });
        } catch (error) { return []; }
    }
    FlatButton {
        text: "Cancel remaining choices"
        visible: app.sync.busy && app.sync.resolutionSummary.total > 0
        onClicked: app.sync.cancelResolutions()
    }
    Repeater {
        model: syncSettings.songConflictGroups
        Rectangle {
            required property var modelData
            Layout.fillWidth: true
            implicitHeight: bulkBanner.implicitHeight + 20
            radius: 8
            color: Theme.surface
            ColumnLayout {
                id: bulkBanner
                anchors.fill: parent
                anchors.margins: 10
                spacing: 8
                Label {
                    text: modelData.items.length + " pending song changes"
                    color: Theme.text
                    font.weight: Font.Medium
                    Layout.fillWidth: true
                }
                Label {
                    text: "Review each song individually below, or resolve all " + modelData.items.length + " current song conflicts at once."
                    color: Theme.muted
                    font.pixelSize: 12
                    wrapMode: Text.WordWrap
                    Layout.fillWidth: true
                }
                RowLayout {
                    FlatButton {
                        text: "Keep desktop for all (" + modelData.items.length + ")"
                        enabled: !app.sync.busy
                        onClicked: app.sync.resolveMany(modelData.items, false)
                    }
                    FlatButton {
                        text: "Keep phone for all (" + modelData.items.length + ")"
                        enabled: !app.sync.busy
                        onClicked: app.sync.resolveMany(modelData.items, true)
                    }
                }
            }
        }
    }
    ListView {
        id: conflictList
        Layout.fillWidth: true
        Layout.preferredHeight: Math.min(640, contentHeight)
        clip: true
        spacing: 16
        cacheBuffer: 120
        model: syncSettings.conflictRows
        ScrollBar.vertical: ScrollBar {}
        delegate: ColumnLayout {
            required property var modelData
            width: conflictList.width
            spacing: 6
            Label { text: syncSettings.title(modelData); color: Theme.text; font.weight: Font.Medium; Layout.fillWidth: true; wrapMode: Text.WordWrap }
            Repeater {
                model: syncSettings.fieldDiffs(modelData)
                RowLayout {
                    required property var modelData
                    Layout.fillWidth: true
                    spacing: 8
                    Rectangle {
                        Layout.fillWidth: true
                        Layout.preferredWidth: 1
                        implicitHeight: desktopCol.implicitHeight + 12
                        radius: 6
                        color: Theme.surface
                        ColumnLayout {
                            id: desktopCol
                            anchors.fill: parent
                            anchors.margins: 6
                            spacing: 2
                            Label { text: modelData.label + " · Desktop"; color: Theme.accent; font.pixelSize: 10; font.weight: Font.Medium }
                            Label { text: modelData.desktop; color: Theme.text; font.pixelSize: 12; wrapMode: Text.WrapAnywhere; Layout.fillWidth: true }
                        }
                    }
                    Rectangle {
                        Layout.fillWidth: true
                        Layout.preferredWidth: 1
                        implicitHeight: phoneCol.implicitHeight + 12
                        radius: 6
                        color: Theme.surface
                        ColumnLayout {
                            id: phoneCol
                            anchors.fill: parent
                            anchors.margins: 6
                            spacing: 2
                            Label { text: modelData.label + " · Phone"; color: Theme.muted; font.pixelSize: 10; font.weight: Font.Medium }
                            Label { text: modelData.phone; color: Theme.text; font.pixelSize: 12; wrapMode: Text.WrapAnywhere; Layout.fillWidth: true }
                        }
                    }
                }
            }
            Label { visible: syncSettings.fieldDiffs(modelData).length === 0; text: syncSettings.details(modelData); color: Theme.muted; font.pixelSize: 11; Layout.fillWidth: true; wrapMode: Text.WrapAnywhere }
            Label { text: "Keep desktop: " + syncSettings.consequence(modelData, false) + "\nKeep phone: " + syncSettings.consequence(modelData, true); color: Theme.muted; font.pixelSize: 11; wrapMode: Text.WordWrap; Layout.fillWidth: true }
            AppCheck {
                visible: modelData.entity === "tracks"
                text: "Apply choice to all " + (syncSettings.appliesToAll(modelData) ? syncSettings.bulkSnapshot.length : (syncSettings.songConflictCounts[modelData.peer] || 0)) + " songs"
                checked: syncSettings.appliesToAll(modelData)
                enabled: !app.sync.busy
                onToggled: syncSettings.chooseAll(modelData, checked)
            }
            Label {
                visible: syncSettings.appliesToAll(modelData)
                text: "The same device wins for these songs, including removals. New or changed conflicts remain for review."
                color: Theme.muted
                font.pixelSize: 12
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
            }
            RowLayout {
                FlatButton { text: syncSettings.appliesToAll(modelData) ? "Keep desktop for all" : "Keep desktop"; enabled: !app.sync.busy; onClicked: syncSettings.choose(modelData, false) }
                FlatButton { text: syncSettings.appliesToAll(modelData) ? "Keep phone for all" : "Keep phone"; enabled: !app.sync.busy; onClicked: syncSettings.choose(modelData, true) }
            }
            Label { visible: modelData.fields.indexOf("deletion") >= 0; text: "Removing a remote library record keeps this device's audio file."; color: Theme.muted; wrapMode: Text.WordWrap; Layout.fillWidth: true }
        }
    }
}
