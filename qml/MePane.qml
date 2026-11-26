import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import SillyPlayer 1.0

Item {
    id: pane
    required property var shell
    property string period: "week"
    property bool artists: false
    property bool insightsOpen: false
    property int revision: 0
    readonly property bool timeTooltipOpen: totalTip.opened || hoverTip.opened
    function dismissTimeTooltip() { totalTime.suppressHover = true; hoverTip.close(); totalTip.close(); }
    property var rows: {
        const observed = revision;
        return pane.visible ? app.statistics.mostPlayed(period, artists) : [];
    }
    property var insightsData: {
        const observed = revision;
        return (pane.visible && pane.insightsOpen) ? app.statistics.insights(period) : ({buckets: [], maxBucket: 0, totalPlays: 0, activeDays: 0, streakDays: 0, topArtist: ({}), topTrack: ({}), peakSummary: "", chartTitle: ""});
    }
    onVisibleChanged: { if (!visible) dismissTimeTooltip(); }
    Connections {
        target: app.statistics
        function onRankingsChanged() { pane.revision++; }
    }
    Connections {
        target: library
        function onChanged() { pane.revision++; }
        function onTracksUpdated(patches) { rankingModel.patchTracks(patches); }
    }
    Timer { interval: 60000; repeat: true; running: pane.visible; onTriggered: pane.revision++ }
    ColumnLayout {
        anchors.fill: parent
        anchors.margins: pane.width > 700 ? 32 : 20
        spacing: 16
        Label { text: "Me!"; color: Theme.text; font.pixelSize: 22; font.weight: Font.DemiBold }
        ColumnLayout {
            spacing: 2
            Label { text: "Total listening time"; color: Theme.muted; font.pixelSize: 12 }
            FlatButton {
                id: totalTime
                objectName: "listeningTime"
                property bool suppressHover: false
                text: app.statistics.summary.compact
                font.pixelSize: 28
                font.weight: Font.Medium
                implicitHeight: 46
                leftPadding: 0; rightPadding: 12
                Accessible.name: "Total listening time: " + app.statistics.summary.exact
                onHoveredChanged: if (!hovered) suppressHover = false
                Keys.onReturnPressed: clicked()
                Keys.onEnterPressed: clicked()
                onClicked: { suppressHover = true; if (totalTip.opened) totalTip.close(); else totalTip.open(); }
                ToolTip {
                    id: hoverTip
                    objectName: "listeningTimeHoverTooltip"
                    parent: totalTime
                    y: totalTime.height + 4
                    visible: pane.visible && totalTime.hovered && !totalTime.suppressHover && !totalTip.visible
                    text: app.statistics.summary.exact
                    padding: 10
                    background: Rectangle { color: Theme.surface; radius: 6; border.width: 1; border.color: Theme.separator }
                    contentItem: Label { text: hoverTip.text; color: Theme.text; font.pixelSize: 13 }
                }
                Popup {
                    id: totalTip
                    objectName: "listeningTimeTooltip"
                    focus: true
                    y: totalTime.height + 4
                    padding: 10
                    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutsideParent
                    onClosed: totalTime.suppressHover = totalTime.hovered
                    background: Rectangle { color: Theme.surface; radius: 6; border.width: 1; border.color: Theme.separator }
                    contentItem: Label { objectName: "listeningTimeExact"; text: app.statistics.summary.exact; color: Theme.text; font.pixelSize: 13; focus: true; Keys.onEscapePressed: pane.dismissTimeTooltip() }
                }
            }
            Label { text: "Tracking since " + app.statistics.summary.since; color: Theme.muted; font.pixelSize: 11 }
        }
        RowLayout {
            Layout.fillWidth: true
            spacing: 8
            Label { text: "Most played"; color: Theme.text; font.pixelSize: 17; Layout.fillWidth: true }
            FlatButton {
                objectName: "toggleInsights"
                text: pane.insightsOpen ? "Hide insights" : "Insights"
                glyph: pane.insightsOpen ? "chevron-up" : "chevron-down"
                outlined: true
                checked: pane.insightsOpen
                onClicked: pane.insightsOpen = !pane.insightsOpen
            }
            Repeater {
                model: [{id:"day",name:"Today"},{id:"week",name:"Week"},{id:"month",name:"Month"},{id:"all",name:"All time"}]
                FlatButton {
                    required property var modelData
                    objectName: "statsPeriod-" + modelData.id
                    text: modelData.name
                    hint: modelData.id === "day" ? "Today" : modelData.id === "week" ? "Last 7 days" : modelData.id === "month" ? "Last 30 days" : "All time"
                    checked: pane.period === modelData.id
                    navigation: true
                    onClicked: pane.period = modelData.id
                }
            }
        }
        ColumnLayout {
            visible: pane.insightsOpen
            Layout.fillWidth: true
            spacing: 12
            GridLayout {
                Layout.fillWidth: true
                columns: pane.width > 760 ? 4 : 2
                columnSpacing: 10
                rowSpacing: 10
                Rectangle {
                    Layout.fillWidth: true; implicitHeight: 68; radius: 8; color: Theme.surface; border.width: 1; border.color: Theme.separator
                    ColumnLayout {
                        anchors.fill: parent; anchors.margins: 10; spacing: 2
                        Label { text: "Most heard artist"; color: Theme.muted; font.pixelSize: 11 }
                        Label { text: (pane.insightsData.topArtist && pane.insightsData.topArtist.name) || "—"; color: Theme.text; font.pixelSize: 14; font.weight: Font.DemiBold; elide: Text.ElideRight; Layout.fillWidth: true }
                        Label { text: (pane.insightsData.topArtist && pane.insightsData.topArtist.playCount) ? pane.insightsData.topArtist.playCount + (pane.insightsData.topArtist.playCount === 1 ? " play" : " plays") : "No plays"; color: Theme.muted; font.pixelSize: 11 }
                    }
                }
                Rectangle {
                    Layout.fillWidth: true; implicitHeight: 68; radius: 8; color: Theme.surface; border.width: 1; border.color: Theme.separator
                    ColumnLayout {
                        anchors.fill: parent; anchors.margins: 10; spacing: 2
                        Label { text: "Top song"; color: Theme.muted; font.pixelSize: 11 }
                        Label { text: (pane.insightsData.topTrack && pane.insightsData.topTrack.title) || "—"; color: Theme.text; font.pixelSize: 14; font.weight: Font.DemiBold; elide: Text.ElideRight; Layout.fillWidth: true }
                        Label { text: (pane.insightsData.topTrack && pane.insightsData.topTrack.playCount) ? pane.insightsData.topTrack.playCount + (pane.insightsData.topTrack.playCount === 1 ? " play" : " plays") : "No plays"; color: Theme.muted; font.pixelSize: 11 }
                    }
                }
                Rectangle {
                    Layout.fillWidth: true; implicitHeight: 68; radius: 8; color: Theme.surface; border.width: 1; border.color: Theme.separator
                    ColumnLayout {
                        anchors.fill: parent; anchors.margins: 10; spacing: 2
                        Label { text: "Plays & activity"; color: Theme.muted; font.pixelSize: 11 }
                        Label { text: (pane.insightsData.totalPlays || 0) + (pane.insightsData.totalPlays === 1 ? " play" : " plays"); color: Theme.text; font.pixelSize: 14; font.weight: Font.DemiBold; elide: Text.ElideRight; Layout.fillWidth: true }
                        Label {
                            text: pane.period === "day"
                                ? (pane.insightsData.uniqueArtists || 0) + " artists · " + (pane.insightsData.streakDays || 0) + "d streak"
                                : (pane.insightsData.activeDays || 0) + " active days · " + (pane.insightsData.streakDays || 0) + "d streak"
                            color: Theme.muted; font.pixelSize: 11; elide: Text.ElideRight; Layout.fillWidth: true
                        }
                    }
                }
                Rectangle {
                    Layout.fillWidth: true; implicitHeight: 68; radius: 8; color: Theme.surface; border.width: 1; border.color: Theme.separator
                    ColumnLayout {
                        anchors.fill: parent; anchors.margins: 10; spacing: 2
                        Label { text: "Peak listening"; color: Theme.muted; font.pixelSize: 11 }
                        Label { text: pane.insightsData.peakSummary || "—"; color: Theme.text; font.pixelSize: 13; font.weight: Font.DemiBold; elide: Text.ElideRight; Layout.fillWidth: true }
                        Label { text: (pane.insightsData.uniqueSongs || 0) + " unique songs"; color: Theme.muted; font.pixelSize: 11 }
                    }
                }
            }
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: 116
                radius: 8
                color: Theme.surface
                border.width: 1
                border.color: Theme.separator
                visible: (pane.insightsData.buckets || []).length > 0
                ColumnLayout {
                    anchors.fill: parent
                    anchors.margins: 12
                    spacing: 6
                    RowLayout {
                        Layout.fillWidth: true
                        Label { text: pane.insightsData.chartTitle || ""; color: Theme.muted; font.pixelSize: 12; Layout.fillWidth: true }
                        Label { text: pane.insightsData.maxBucket > 0 ? "Peak: " + pane.insightsData.maxBucket + (pane.insightsData.maxBucket === 1 ? " play" : " plays") : "No plays in range"; color: Theme.muted; font.pixelSize: 11 }
                    }
                    RowLayout {
                        Layout.fillWidth: true
                        Layout.fillHeight: true
                        spacing: (pane.insightsData.buckets || []).length > 10 ? 2 : 8
                        Repeater {
                            model: pane.insightsData.buckets || []
                            Item {
                                required property var modelData
                                Layout.fillWidth: true
                                Layout.fillHeight: true
                                readonly property real ratio: pane.insightsData.maxBucket > 0 ? (modelData.count / pane.insightsData.maxBucket) : 0
                                ColumnLayout {
                                    anchors.fill: parent
                                    spacing: 3
                                    Item {
                                        Layout.fillWidth: true
                                        Layout.fillHeight: true
                                        Rectangle {
                                            anchors.bottom: parent.bottom
                                            anchors.horizontalCenter: parent.horizontalCenter
                                            width: Math.min(parent.width, (pane.insightsData.buckets || []).length > 10 ? 12 : 34)
                                            height: modelData.count > 0 ? Math.max(4, Math.round(parent.height * ratio)) : 2
                                            radius: 3
                                            color: modelData.count > 0 ? Theme.accent : Theme.separator
                                            opacity: modelData.count > 0 ? 0.9 : 0.45
                                        }
                                        ToolTip.visible: barHover.containsMouse
                                        ToolTip.text: (modelData.tooltip || modelData.label || "") + ": " + modelData.count + (modelData.count === 1 ? " play" : " plays")
                                        MouseArea { id: barHover; anchors.fill: parent; hoverEnabled: true }
                                    }
                                    Label {
                                        Layout.fillWidth: true
                                        text: modelData.label || ""
                                        horizontalAlignment: Text.AlignHCenter
                                        color: Theme.muted
                                        font.pixelSize: 10
                                        elide: Text.ElideNone
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        RowLayout {
            spacing: 4
            FlatButton { objectName: "statsSongs"; text: "Songs"; navigation: true; checked: !pane.artists; onClicked: pane.artists = false }
            FlatButton { objectName: "statsArtists"; text: "Artists"; navigation: true; checked: pane.artists; onClicked: pane.artists = true }
        }
        ListView {
            id: rankings
            objectName: "statsRankings"
            Layout.fillWidth: true; Layout.fillHeight: true
            clip: true
            spacing: 3
            model: StableListModel { id: rankingModel; rows: pane.rows }
            property var scrollPositions: ({})
            property string shownView: "week:songs"
            function rememberView() {
                scrollPositions[shownView] = contentY;
                shownView = pane.period + (pane.artists ? ":artists" : ":songs");
                const saved = scrollPositions[shownView] || 0;
                Qt.callLater(function() { rankings.contentY = Math.max(0, Math.min(saved, rankings.contentHeight - rankings.height)); });
            }
            Connections {
                target: pane
                function onPeriodChanged() { rankings.rememberView(); }
                function onArtistsChanged() { rankings.rememberView(); }
            }
            ScrollBar.vertical: ScrollBar {}
            delegate: Item {
                required property var record
                width: rankings.width; height: 72
                TrackRow {
                    anchors.fill: parent
                    visible: !pane.artists
                    track: pane.artists ? {id:"", duration:0, available:true} : record
                    shell: pane.shell
                    sourceTracks: pane.rows
                    sourceName: "Most played · " + (pane.period === "day" ? "Today" : pane.period === "week" ? "Week" : pane.period === "month" ? "Month" : "All time")
                    sourceId: "stats:" + pane.period
                    statistic: record.playCount + (record.playCount === 1 ? " play" : " plays")
                }
                CollectionRow {
                    anchors.fill: parent
                    visible: pane.artists
                    title: record.name || ""
                    subtitle: record.playCount + (record.playCount === 1 ? " play" : " plays")
                    cover: record.cover || ""
                    onClicked: pane.shell.openArtist(record)
                }
            }
            Label {
                anchors.centerIn: parent
                width: parent.width - 24
                visible: rankings.count === 0
                text: "Listen to songs from your library to start your statistics."
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.WordWrap
                color: Theme.muted; font.pixelSize: 13
            }
        }
    }
}
