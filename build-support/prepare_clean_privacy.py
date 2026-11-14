"""Remove the legacy privacy controls and behavior from a pinned preview17 decode."""

import argparse
import re
import shutil
import zipfile
from pathlib import Path

from prepare_repack import METHOD, digest, find_smali, within_work
from prepare_route_update import PREVIEW17


FALSE = "    .locals 1\n    const/4 v0, 0x0\n    return v0"
NULL = "    .locals 1\n    const/4 v0, 0x0\n    return-object v0"
NOOP = "    .locals 0\n    return-void"
EMPTY_LIST = (
    "    .locals 1\n"
    "    new-instance v0, Ljava/util/ArrayList;\n"
    "    invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V\n"
    "    return-object v0"
)
NORMAL_FLAGS = {
    "sendReadMessagePackets": True,
    "sendReadStoriesPackets": True,
    "sendOnlinePackets": True,
    "sendUploadProgress": True,
    "sendOfflinePacketAfterOnline": False,
    "showGhostInDrawer": False,
    "showGhostModeStatus": False,
    "GhostScheduledSend": False,
    "AskBeforeOpeningStory": False,
    "EnableSaveDeletedMessages": False,
    "SaveDeletedInPrivateChats": False,
    "SaveDeletedInGroups": False,
    "SaveDeletedInChannels": False,
    "SaveDeletedMessageForBot": False,
    "SaveDeletedMessageForBotUser": False,
    "ChatMenuItemViewDeleted": False,
    "ChatMenuItemClearDeleted": False,
    "ReplyToDeletedAsQuote": False,
}


def replace_once(source, old, new, description):
    if source.count(old) != 1:
        raise ValueError(f"Expected one pinned match for {description}")
    return source.replace(old, new, 1)


def replace_body(source, signature, body):
    matches = [match for match in METHOD.finditer(source) if match["head"].split()[-1] == signature]
    if len(matches) != 1:
        raise ValueError(f"Expected one pinned method {signature}, found {len(matches)}")
    entry = matches[0]
    return source[:entry.start()] + f".method {entry['head']}\n{body}\n.end method\n" + source[entry.end():]


def normal_requests(source):
    pattern = (
        r"(?m)(^    :cond_0\n)"
        r"    invoke-static/range \{p1 \.\. p2\}, "
        r"Lcom/radolyn/ayugram/utils/AyuGhostUtils;->interceptRequest[^\n]*\n"
        r"(?s:.*?)"
        r"    invoke-virtual \{v0\}, Lcom/radolyn/ayugram/utils/AyuGhostUtils\$InterceptResult;"
        r"->effectiveOnComplete\(\)Lorg/telegram/tgnet/RequestDelegate;\n\n"
        r"    move-result-object v6"
    )
    result, count = re.subn(pattern, r"\g<1>    move-object/from16 v6, p2", source)
    if count != 1 or "->interceptRequest(" in result:
        raise ValueError("Pinned ghost request dispatch changed")
    return result


def restrict_legacy_flags(source):
    original = "    iget-object p0, p0, Ltw/nekomimi/nekogram/config/ConfigItem;->value:Ljava/lang/Object;\n"
    getter = next(match for match in METHOD.finditer(source) if match["head"] == "public Bool()Z")
    if original not in getter["body"]:
        raise ValueError("Pinned ConfigItem boolean getter changed")
    comparisons = []
    for key, enabled in NORMAL_FLAGS.items():
        comparisons.extend((
            f'    const-string v1, "{key}"',
            "    invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z",
            "    move-result v1",
            f"    if-nez v1, :tgru_normal_{int(enabled)}",
        ))
    body = "\n".join((
        "    .locals 2",
        "    iget-object v0, p0, Ltw/nekomimi/nekogram/config/ConfigItem;->key:Ljava/lang/String;",
        *comparisons,
        original.rstrip(),
        "    check-cast p0, Ljava/lang/Boolean;",
        "    invoke-virtual {p0}, Ljava/lang/Boolean;->booleanValue()Z",
        "    move-result p0",
        "    return p0",
        "    :tgru_normal_0",
        "    const/4 v0, 0x0",
        "    return v0",
        "    :tgru_normal_1",
        "    const/4 v0, 0x1",
        "    return v0",
    ))
    return replace_body(source, "Bool()Z", body)


def no_ghost_drawer(source):
    old = (
        "    invoke-static {p1}, Lorg/telegram/messenger/family/FamilyNavigation;"
        "->ghostMenuInfo(Ljava/lang/Object;)Ljava/lang/Object;\n\n"
        "    move-result-object v0\n\n"
        "    check-cast v0, Lapp/exteraless/drawer/MainMenuHelper$MenuItemInfo;\n\n"
        "    return-object v0"
    )
    # Keep the custom-layout item ID reserved so old layouts retain their other items.
    return replace_once(source, old, "    const/4 v0, 0x0\n    return-object v0", "legacy drawer item")


def no_ghost_drawer_description(source):
    if ":tgru_describe_item" in source:
        raise ValueError("Drawer customization already updated")
    old = (
        ".method public static describeItem(I)Lapp/exteraless/drawer/MainMenuHelper$MenuItemInfo;\n"
        "    .locals 3\n"
    )
    return replace_once(source, old, old + (
        "    const/16 v0, 0x6b\n"
        "    if-ne p0, v0, :tgru_describe_item\n"
        "    const/4 v0, 0x0\n"
        "    return-object v0\n"
        "    :tgru_describe_item\n"
    ), "drawer customization ghost item")


def no_ayu_moments_row(source):
    pattern = (
        r"    const-string v1, \"ayuMoments\"\n\n"
        r"    filled-new-array \{v1\}, \[Ljava/lang/String;\n\n"
        r"    move-result-object v1\n\n"
        r"    invoke-virtual \{p0, v1\}, "
        r"Ltw/nekomimi/nekogram/settings/BaseNekoSettingsActivity;->addRow\(\[Ljava/lang/String;\)I\n\n"
        r"    move-result v1\n\n"
        r"(    iput v1, p0, Lapp/exteraless/settings/OpenExteraOtherActivity;->ayuMomentsRow:I)"
    )
    source, count = re.subn(pattern, r"    const/4 v1, -0x1\n\g<1>", source)
    if count != 1:
        raise ValueError("Pinned Ayu settings row changed")
    return source


def no_archive_data(source):
    for signature, value in (
        ("getDeletedCount(JJ)I", FALSE),
        ("isAyuDeletedMessageId(JJI)Z", FALSE),
        ("getMediaPath(JJI)Ljava/lang/String;", NULL),
        ("getMessage(JJI)Lcom/radolyn/ayugram/database/entities/DeletedMessageFull;", NULL),
        ("onMessageDeleted(Lcom/radolyn/ayugram/messages/AyuSavePreferences;)V", NOOP),
        ("onMessageDeleted(Lcom/radolyn/ayugram/messages/AyuSavePreferences;Z)V", NOOP),
        ("onMessageDeletedInner(Lcom/radolyn/ayugram/messages/AyuSavePreferences;)V", NOOP),
        ("processDeletedReactions(JLorg/telegram/tgnet/TLRPC$TL_messageReactions;)V", NOOP),
        ("updateMediaPath(JJILjava/lang/String;)V", NOOP),
    ):
        source = replace_body(source, signature, value)
    for signature in (
        "getExistingMessageIds(JJLjava/util/List;)",
        "getLatestMessages(JJI)",
        "getMessages(JJJJI)",
        "getMessagesByIds(JJLjava/util/List;)",
        "getMessagesGroupedIn(JJLjava/util/List;)",
        "getOlderMessagesBefore(JJII)",
        "getThreadMessages(JJJJJI)",
        "getTopicMessages(JJJJJI)",
    ):
        source = replace_body(source, signature + "Ljava/util/List;", EMPTY_LIST)
    return source


def prepare(decoded):
    changes = {}

    def edit(class_name, transform):
        path = find_smali(decoded, class_name)
        source = changes[path] if path in changes else path.read_text("utf-8")
        changes[path] = transform(source)

    def method(class_name, signature, body):
        edit(class_name, lambda source: replace_body(source, signature, body))

    edit("org.telegram.tgnet.ConnectionsManager", normal_requests)
    edit("tw.nekomimi.nekogram.config.ConfigItem", restrict_legacy_flags)
    edit("org.telegram.ui.DialogsActivity", lambda source: replace_once(
        source,
        "    invoke-static {v0, p0}, Lorg/telegram/messenger/family/FamilyNavigation;"
        "->addGhostMenu(Ljava/lang/Object;Ljava/lang/Object;)V",
        "    # No legacy privacy item.",
        "chat overflow ghost item",
    ))
    edit("app.exteraless.drawer.MainMenuHelper",
         lambda source: no_ghost_drawer_description(no_ghost_drawer(source)))
    edit("app.exteraless.settings.OpenExteraOtherActivity", no_ayu_moments_row)
    for screen in (
        "tw.nekomimi.nekogram.settings.GhostModeActivity",
        "tw.nekomimi.nekogram.settings.NekoExperimentalSettingsActivity",
        "app.exteraless.settings.OpenExteraAyuMomentsActivity",
    ):
        method(screen, "onFragmentCreate()Z", FALSE)

    config = "tw.nekomimi.nekogram.NekoConfig"
    method(config, "isGhostModeActive()Z", FALSE)
    method(config, "setGhostMode(Z)V", NOOP)
    method(config, "toggleGhostMode()V", NOOP)
    ghost = "com.radolyn.ayugram.utils.AyuGhostUtils"
    result = "Lcom/radolyn/ayugram/utils/AyuGhostUtils$InterceptResult;"
    delegate = "Lorg/telegram/tgnet/RequestDelegate;"
    method(ghost, f"interceptRequest(Lorg/telegram/tgnet/TLObject;{delegate}){result}", (
        f"    .locals 2\n"
        f"    new-instance v0, {result}\n"
        f"    const/4 v1, 0x0\n"
        f"    invoke-direct {{v0, v1, p1}}, {result}-><init>(Z{delegate})V\n"
        f"    return-object v0"
    ))
    method(ghost, "bypass(Lorg/telegram/tgnet/TLObject;)Lorg/telegram/tgnet/TLObject;",
           "    .locals 0\n    return-object p0")
    method(ghost, "performStatusRequest(Ljava/lang/Boolean;)V", NOOP)

    save = "com.radolyn.ayugram.messages.AyuSavePreferences"
    method(save, "saveDeletedMessageFor(IJJ)Z", FALSE)
    method(save, "saveDeletedMessageFor(IJLorg/telegram/messenger/MessageObject;)Z", FALSE)
    archive = "com.radolyn.ayugram.messages.AyuDeletedDialogs"
    method(archive, "apply(ILcom/radolyn/ayugram/database/entities/DeletedDialog;Lorg/telegram/tgnet/TLRPC$Message;)Z", FALSE)
    method(archive, "isEnabledFor(IJ)Z", FALSE)
    method(archive, "loadLastMessage(IJJ)Lorg/telegram/tgnet/TLRPC$Message;", NULL)
    method(archive, "onDialogEmptied(IJ)V", NOOP)
    method(archive, "restore(I)V", NOOP)
    edit("com.radolyn.ayugram.messages.AyuMessagesController", no_archive_data)
    return changes


def retain_unchanged_dex(base, decoded, changes):
    """Keep every untouched DEX exactly as it appears in the verified APK."""
    affected = {path.relative_to(decoded).parts[0] for path in changes}
    saved = decoded.parent / "unchanged-smali"
    with zipfile.ZipFile(base) as archive:
        dex = [name for name in archive.namelist() if re.fullmatch(r"classes(?:\d+)?\.dex", name)]
        unchanged = {
            "smali" if name == "classes.dex" else "smali_" + name[:-4]: name
            for name in dex
        }
        unchanged = {folder: name for folder, name in unchanged.items() if folder not in affected}
        if saved.exists() or any(not (decoded / folder).is_dir() or (decoded / name).exists()
                                 for folder, name in unchanged.items()):
            raise ValueError("Expected unmodified Smali directories for untouched DEX files")
        saved.mkdir()
        for folder, name in unchanged.items():
            shutil.move(decoded / folder, saved / folder)
            (decoded / name).write_bytes(archive.read(name))
    return sorted(unchanged.values())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--retain-unchanged-dex", action="store_true")
    args = parser.parse_args()
    decoded = within_work(args.work) / "decoded"
    if digest(args.base) != PREVIEW17 or not (decoded / "apktool.yml").is_file():
        raise ValueError("Expected the verified preview17 APK and full Apktool decode")
    changes = prepare(decoded)
    if not args.check:
        if args.retain_unchanged_dex:
            print("Retained unchanged DEX:", ", ".join(retain_unchanged_dex(args.base, decoded, changes)))
        for path, source in changes.items():
            path.write_text(source, encoding="utf-8", newline="\n")
    print(f"{'Verified' if args.check else 'Updated'} pinned privacy hooks in {len(changes)} classes")


if __name__ == "__main__":
    main()
