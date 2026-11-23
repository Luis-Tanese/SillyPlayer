import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

ItemDelegate {
    id: row
    property string title: ""
    property string subtitle: ""
    property string cover: ""
    property string glyph: ""
    property bool actions: false
    property bool selectable: false
    property bool selected: false
    signal menuRequested(var anchor)
    implicitHeight: 68
    padding: 10
    background: Rectangle { radius: 7; border.width: row.activeFocus ? 1 : 0; border.color: Theme.accent; color: row.selected ? Qt.alpha(Theme.accent, 0.12) : row.hovered ? Qt.alpha(Theme.hover, 0.5) : "transparent" }
    contentItem: RowLayout {
        spacing: 14
        AppCheck {
            visible: row.selectable; checked: row.selected
            onClicked: row.clicked()
            Accessible.name: "Select " + row.title
        }
        Item {
            Layout.preferredWidth: 46
            Layout.preferredHeight: 46
            Art { anchors.fill: parent; source: row.cover; visible: !row.glyph }
            AppIcon { anchors.centerIn: parent; width: 22; height: 22; glyph: row.glyph; visible: !!row.glyph }
        }
        ColumnLayout {
            Layout.fillWidth: true
            spacing: 3
            Label { objectName: "collectionTitle"; text: row.title; color: Theme.text; elide: Text.ElideRight; Layout.fillWidth: true; font.pixelSize: 14 }
            Label { text: row.subtitle; visible: !!text; color: Theme.muted; elide: Text.ElideRight; Layout.fillWidth: true; font.pixelSize: 11 }
        }
        FlatButton { glyph: "ellipsis"; hint: "Collection actions"; visible: row.actions; onClicked: row.menuRequested(this) }
    }
}
