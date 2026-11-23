import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Rectangle {
    id: row
    required property var track
    required property var shell
    objectName: "songRow"
    property var selectionController: null
    property bool selected: false
    property string statistic: ""
    property var sourceTracks: []
    property string sourceId: ""
    property string sourceName: "Songs"
    height: 72
    radius: 9
    color: selected ? Qt.alpha(Theme.accent, 0.12) : hover.hovered ? Qt.alpha(Theme.hover, 0.5) : track.id === app.current.id ? Qt.alpha(Theme.accent, 0.055) : "transparent"
    function activate(modifiers, artwork) {
        if (selectionController && selectionController.rowClick(track, modifiers)) return;
        app.playTrack(track.id, sourceTracks, sourceName, sourceId, track.memberId || "");
        if (artwork) shell.expand();
    }
    HoverHandler {
        id: hover
    }
    MouseArea {
        anchors.fill: parent
        onClicked: mouse => row.activate(mouse.modifiers, false)
    }
    RowLayout {
        anchors.fill: parent
        anchors.leftMargin: 10
        anchors.rightMargin: 10
        spacing: row.width < 400 ? 8 : 14
        AppCheck {
            objectName: "songSelectionCheck"
            visible: !!row.selectionController && row.selectionController.selectionMode
            checked: row.selected
            Layout.preferredWidth: 26
            Accessible.name: "Select " + (row.track.title || "song")
            onClicked: row.selectionController.rowClick(row.track, 0)
        }
        Art {
            objectName: "trackArt"
            source: track.art || ""
            implicitWidth: row.width < 400 ? 40 : 46
            implicitHeight: implicitWidth
            interactive: true
            actionGlyph: row.selectionController && row.selectionController.selectionMode ? "check" : "play"
            onClicked: modifiers => row.activate(modifiers, true)
        }
        ColumnLayout {
            Layout.fillWidth: true
            spacing: 3
            Label {
                text: track.title || "Unknown title"
                color: track.id === app.current.id ? Theme.accent : Theme.text
                font.pixelSize: 14
                elide: Text.ElideRight
                Layout.fillWidth: true
            }
            Label {
                text: (track.artist || "Unknown artist") + (track.available ? "" : " · Unavailable")
                color: Theme.muted
                font.pixelSize: 12
                elide: Text.ElideRight
                Layout.fillWidth: true
            }
        }
        Label {
            objectName: "trackSpeedBadge"
            text: app.modifierLabel(row.track)
            visible: !!text
            Layout.maximumWidth: row.width < 420 ? 60 : 125
            color: Theme.accent; font.pixelSize: 10; elide: Text.ElideRight
            ToolTip.visible: badgeHover.hovered
            ToolTip.text: text
            HoverHandler { id: badgeHover }
        }
        Label {
            text: row.statistic
            visible: !!text
            color: Theme.muted
            font.pixelSize: 11
        }
        Label {
            text: app.time(track.duration)
            color: Theme.muted
            font.pixelSize: 12
        }
        FlatButton {
            glyph: "ellipsis"
            hint: "Track actions"
            onClicked: shell.trackMenu(track, this)
        }
    }
}
