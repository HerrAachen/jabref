package org.jabref.gui.actions;

import java.util.List;

import javax.swing.JOptionPane;

import org.jabref.Globals;
import org.jabref.gui.BasePanel;
import org.jabref.gui.DialogService;
import org.jabref.gui.JabRefFrame;
import org.jabref.gui.cleanup.CleanupPresetPanel;
import org.jabref.gui.undo.NamedCompound;
import org.jabref.gui.undo.UndoableFieldChange;
import org.jabref.gui.util.DefaultTaskExecutor;
import org.jabref.gui.worker.AbstractWorker;
import org.jabref.logic.cleanup.CleanupPreset;
import org.jabref.logic.cleanup.CleanupWorker;
import org.jabref.logic.l10n.Localization;
import org.jabref.model.FieldChange;
import org.jabref.model.entry.BibEntry;
import org.jabref.preferences.JabRefPreferences;

public class CleanupAction extends AbstractWorker {

    private final BasePanel panel;
    private final JabRefFrame frame;
    private final DialogService dialogService;

    /**
     * Global variable to count unsuccessful renames
     */
    private int unsuccessfulRenames;

    private boolean canceled;
    private int modifiedEntriesCount;
    private final JabRefPreferences preferences;

    public CleanupAction(BasePanel panel, JabRefPreferences preferences) {
        this.panel = panel;
        this.frame = panel.frame();
        this.preferences = preferences;
        this.dialogService = frame.getDialogService();
    }

    @Override
    public void init() {
        canceled = false;
        modifiedEntriesCount = 0;
        if (panel.getSelectedEntries().isEmpty()) { // None selected. Inform the user to select entries first.
            JOptionPane.showMessageDialog(null, Localization.lang("First select entries to clean up."),
                    Localization.lang("Cleanup entry"), JOptionPane.INFORMATION_MESSAGE);
            canceled = true;
            return;
        }
        panel.output(Localization.lang("Doing a cleanup for %0 entries...",
                Integer.toString(panel.getSelectedEntries().size())));
    }

    @Override
    public void run() {
        if (canceled) {
            return;
        }
        CleanupPresetPanel presetPanel = new CleanupPresetPanel(panel.getBibDatabaseContext(),
                CleanupPreset.loadFromPreferences(preferences));
        int choice = showDialog(presetPanel);
        if (choice != JOptionPane.OK_OPTION) {
            canceled = true;
            return;
        }
        CleanupPreset cleanupPreset = presetPanel.getCleanupPreset();
        cleanupPreset.storeInPreferences(preferences);

        if (cleanupPreset.isRenamePDF() && Globals.prefs.getBoolean(JabRefPreferences.ASK_AUTO_NAMING_PDFS_AGAIN)) {

            boolean autogeneratePressed = dialogService.showConfirmationDialogWithOptOutAndWait(Localization.lang("Autogenerate PDF Names"),
                    Localization.lang("Auto-generating PDF-Names does not support undo. Continue?"),
                    Localization.lang("Autogenerate PDF Names"),
                    Localization.lang("Cancel"),
                    Localization.lang("Disable this confirmation dialog"),
                    optOut -> Globals.prefs.putBoolean(JabRefPreferences.ASK_AUTO_NAMING_PDFS_AGAIN, !optOut));

            if (!autogeneratePressed) {
                canceled = true;
                return;
            }
        }

        for (BibEntry entry : panel.getSelectedEntries()) {
            // undo granularity is on entry level
            NamedCompound ce = new NamedCompound(Localization.lang("Cleanup entry"));

            doCleanup(cleanupPreset, entry, ce);

            ce.end();
            if (ce.hasEdits()) {
                modifiedEntriesCount++;
                panel.getUndoManager().addEdit(ce);
            }
        }
    }

    @Override
    public void update() {
        if (canceled) {
            return;
        }
        if (unsuccessfulRenames > 0) { //Rename failed for at least one entry
            dialogService.showErrorDialogAndWait(
                    Localization.lang("Autogenerate PDF Names"),
                    Localization.lang("File rename failed for %0 entries.", Integer.toString(unsuccessfulRenames)));
        }
        if (modifiedEntriesCount > 0) {
            panel.updateEntryEditorIfShowing();
            panel.markBaseChanged();
        }
        String message;
        switch (modifiedEntriesCount) {
            case 0:
                message = Localization.lang("No entry needed a clean up");
                break;
            case 1:
                message = Localization.lang("One entry needed a clean up");
                break;
            default:
                message = Localization.lang("%0 entries needed a clean up", Integer.toString(modifiedEntriesCount));
                break;
        }
        panel.output(message);
    }

    private int showDialog(CleanupPresetPanel presetPanel) {
        String dialogTitle = Localization.lang("Cleanup entries");
        Object[] messages = {Localization.lang("What would you like to clean up?"), presetPanel.getScrollPane()};
        return JOptionPane.showConfirmDialog(null, messages, dialogTitle, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE);
    }

    /**
     * Runs the cleanup on the entry and records the change.
     */
    private void doCleanup(CleanupPreset preset, BibEntry entry, NamedCompound ce) {
        // Create and run cleaner
        CleanupWorker cleaner = new CleanupWorker(panel.getBibDatabaseContext(), preferences.getCleanupPreferences(
                Globals.journalAbbreviationLoader));
        List<FieldChange> changes = DefaultTaskExecutor.runInJavaFXThread(() -> cleaner.cleanup(preset, entry));

        unsuccessfulRenames = cleaner.getUnsuccessfulRenames();

        if (changes.isEmpty()) {
            return;
        }

        // Register undo action
        for (FieldChange change : changes) {
            ce.addEdit(new UndoableFieldChange(change));
        }
    }

}
