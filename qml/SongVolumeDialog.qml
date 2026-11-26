import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

AppDialog {
    id: dialog
    objectName: "songVolumeDialog"
    parent: Overlay.overlay
    anchors.centerIn: parent
    width: Math.min(parent.width - 40, 400)
    title: "Song volume"
    standardButtons: Dialog.Close
    property string trackId: ""
    property string trackTitle: ""
    property bool dirty: false
    function show(track) {
        if (!track.id) return;
        commit();
        trackId = track.id;
        trackTitle = track.title || "Unknown title";
        level.value = track.volumePercent === undefined ? 100 : track.volumePercent;
        dirty = false;
        open();
    }
    function commit() {
        preview.stop();
        if (!dirty) return;
        dirty = false;
        app.applyTrackVolume(trackId, level.value);
    }
    function choose(value) {
        level.value = value;
        dirty = true;
        commit();
    }
    Timer {
        id: preview
        interval: 32
        onTriggered: app.previewTrackVolume(dialog.trackId, level.value)
    }
    onClosed: commit()
    ColumnLayout {
        width: parent.width
        spacing: 12
        Label {
            text: dialog.trackTitle
            color: Theme.muted
            elide: Text.ElideRight
            Layout.fillWidth: true
        }
        GridLayout {
            columns: 3
            Layout.fillWidth: true
            Repeater {
                model: [50, 75, 100, 150, 200, 250, 300]
                FlatButton {
                    required property int modelData
                    Layout.fillWidth: true
                    Layout.minimumWidth: 0
                    checked: Math.abs(modelData - level.value) < 0.5
                    text: modelData + "%"
                    onClicked: dialog.choose(modelData)
                }
            }
        }
        AppSlider {
            id: level
            objectName: "songVolumeSlider"
            from: 0; to: 300; stepSize: 1; value: 100; suffix: "%"
            Layout.fillWidth: true
            Accessible.name: "Song volume adjustment"
            onMoved: {
                dialog.dirty = true;
                if (pressed) { if (!preview.running) preview.start(); }
                else dialog.commit();
            }
            onPressedChanged: if (!pressed) dialog.commit()
        }
        RowLayout {
            Layout.fillWidth: true
            AppField {
                objectName: "songVolumeInput"
                text: Math.round(level.value).toString()
                validator: IntValidator { bottom: 0; top: 300 }
                Layout.preferredWidth: 85
                Accessible.name: "Song volume percent"
                onEditingFinished: {
                    const n = Number(text);
                    if (text.trim() && Number.isInteger(n) && n >= 0 && n <= 300) dialog.choose(n);
                    else text = Math.round(level.value).toString();
                }
            }
            Label { text: "%"; color: Theme.muted }
            Item { Layout.fillWidth: true }
            FlatButton {
                objectName: "resetSongVolume"
                text: "Reset to 100%"
                onClicked: dialog.choose(100)
            }
        }
        Label {
            objectName: "songVolumeOutput"
            text: "Main " + Math.round(music.volume) + "% · Output " + Math.round(Math.min(300, music.volume * level.value / 100)) + "%"
            color: Theme.text
            wrapMode: Text.WordWrap
            Layout.fillWidth: true
        }
        Label {
            text: "Saved for this song. 100% is unchanged. Scales from your main volume slider up to 300%; boost can distort peaks."
            color: Theme.muted
            font.pixelSize: 12
            wrapMode: Text.WordWrap
            Layout.fillWidth: true
        }
    }
}
