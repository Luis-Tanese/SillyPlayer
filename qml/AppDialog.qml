import QtQuick
import QtQuick.Controls

Dialog {
    id: control
    modal: true
    padding: 24
    topPadding: 14
    bottomPadding: 20
    background: Rectangle {
        color: Theme.bg
        radius: 12
        border.color: Theme.separator
    }
    header: Label {
        text: control.title
        visible: text.length > 0
        color: Theme.text
        font.pixelSize: 19
        font.weight: Font.DemiBold
        leftPadding: 24
        rightPadding: 24
        topPadding: 22
        bottomPadding: 12
        elide: Text.ElideRight
    }
    footer: DialogButtonBox {
        visible: count > 0
        background: Item {
        }
        leftPadding: 20
        rightPadding: 20
        bottomPadding: 14
        spacing: 8
        delegate: FlatButton {
            objectName: "dialogButton"
        }
    }
    Overlay.modal: Rectangle {
        color: "#66000000"
    }
}
