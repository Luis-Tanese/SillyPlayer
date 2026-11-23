import QtQuick
import QtQuick.Controls.impl

IconImage {
    property string glyph: ""
    source: glyph ? "qrc:/assets/icons/" + glyph + ".svg" : ""
    color: Theme.muted
    sourceSize.width: width
    sourceSize.height: height
    width: 18
    height: 18
    fillMode: Image.PreserveAspectFit
}
