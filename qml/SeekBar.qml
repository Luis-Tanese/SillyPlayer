import QtQuick
import QtQuick.Controls

Slider {
    id: seek
    objectName: thin ? "compactSeek" : "expandedSeek"
    required property var backend
    property bool thin: false
    property double scrubPosition: 0
    property double displayedPosition: pressed ? scrubPosition : backend.position
    property var dragGeneration: 0
    from: 0
    to: Math.max(1, backend.duration)
    enabled: backend.loaded && backend.seekable && backend.duration > 0
    implicitHeight: thin ? 16 : 28
    leftPadding: 0
    rightPadding: 0
    onMoved: {
        scrubPosition = value;
        if (!pressed) backend.seek(value);
    }
    onPressedChanged: {
        if (pressed) { dragGeneration = backend.generation; scrubPosition = value; }
        else if (dragGeneration === backend.generation && enabled) backend.seek(scrubPosition);
    }
    Binding {
        target: seek; property: "value"; value: backend.position
        when: !seek.pressed; restoreMode: Binding.RestoreNone
    }
    background: Rectangle {
        y: (seek.height - height) / 2
        width: seek.availableWidth
        height: thin ? 3 : 4
        radius: 2
        color: Theme.rail
        Rectangle { width: seek.visualPosition * parent.width; height: parent.height; radius: 2; color: seek.enabled ? Theme.accent : Theme.muted }
    }
    handle: Rectangle {
        x: seek.leftPadding + seek.visualPosition * (seek.availableWidth - width)
        y: (seek.height - height) / 2
        width: 10; height: 10; radius: 5
        opacity: seek.pressed || seek.hovered || !seek.thin ? 1 : 0
        color: Theme.accent
    }
    ToolTip.visible: hovered && enabled
    ToolTip.text: app.time(displayedPosition) + " / " + app.time(backend.duration)
    Accessible.name: "Seek position"
}
