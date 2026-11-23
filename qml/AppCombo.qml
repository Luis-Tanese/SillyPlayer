import QtQuick
import QtQuick.Controls

ComboBox {
    id: control
    implicitHeight: 42
    implicitWidth: Math.max(150, implicitContentWidth + 56)
    leftPadding: 14
    rightPadding: 34
    font.pixelSize: 13
    background: Rectangle {
        radius: 8
        color: control.down ? Theme.hover : Theme.surface
        border.width: 1
        border.color: control.activeFocus ? Theme.accent : Theme.separator
    }
    contentItem: Text {
        text: control.displayText
        color: control.enabled ? Theme.text : Theme.muted
        font: control.font
        verticalAlignment: Text.AlignVCenter
        elide: Text.ElideRight
    }
    indicator: AppIcon {
        glyph: "chevron-down"
        x: control.width - width - 11
        y: (control.height - height) / 2
        width: 15
        height: 15
    }
    delegate: ItemDelegate {
        id: option
        objectName: "comboOption"
        required property int index
        width: control.width - 12
        implicitHeight: 38
        text: control.textAt(index)
        highlighted: control.highlightedIndex === index
        contentItem: Text {
            objectName: "comboOptionText"
            text: option.text
            color: Theme.text
            font: control.font
            verticalAlignment: Text.AlignVCenter
            elide: Text.ElideRight
        }
        background: Rectangle {
            radius: 5
            color: option.highlighted ? Theme.hover : "transparent"
        }
    }
    popup: Popup {
        y: control.height + 5
        width: control.width
        implicitHeight: Math.min(contentItem.implicitHeight + 12, 310)
        padding: 6
        contentItem: ListView {
            clip: true
            implicitHeight: contentHeight
            model: control.popup.visible ? control.delegateModel : null
            currentIndex: control.highlightedIndex
            ScrollIndicator.vertical: ScrollIndicator {
            }
        }
        background: Rectangle {
            color: Theme.surface
            radius: 8
            border.color: Theme.separator
        }
    }
}
