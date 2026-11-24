import QtQuick
import QtQuick.Controls

TextField {
    id: field
    property bool search: false
    implicitHeight: 42
    leftPadding: search ? 43 : 14
    rightPadding: search ? 40 : 14
    topPadding: 10
    bottomPadding: 10
    color: Theme.text
    placeholderTextColor: Theme.muted
    selectionColor: Theme.accent
    selectedTextColor: Theme.base
    selectByMouse: true
    font.pixelSize: 13
    background: Rectangle {
        radius: field.search ? 12 : 8
        color: Theme.surface
        border.width: 1
        border.color: field.activeFocus ? Qt.alpha(Theme.accent, 0.72) : field.hovered ? Qt.alpha(Theme.accent, 0.3) : Theme.separator
    }
    FlatButton {
        visible: field.search
        anchors.left: parent.left
        anchors.leftMargin: 5
        anchors.verticalCenter: parent.verticalCenter
        glyph: "search"
        hint: "Search"
        icon.color: field.activeFocus ? Theme.accent : Theme.muted
        onClicked: field.accepted()
    }
    FlatButton {
        visible: field.search && field.text.length > 0
        anchors.right: parent.right
        anchors.rightMargin: 5
        anchors.verticalCenter: parent.verticalCenter
        glyph: "x"
        hint: "Clear search input"
        icon.width: 14
        icon.height: 14
        onClicked: {
            field.clear();
            field.forceActiveFocus();
        }
    }
}
