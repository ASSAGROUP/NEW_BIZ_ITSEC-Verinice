/*******************************************************************************
 * Copyright (c) 2012 Daniel Murygin.
 *
 * This program is free software: you can redistribute it and/or 
 * modify it under the terms of the GNU Lesser General Public License 
 * as published by the Free Software Foundation, either version 3 
 * of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful,    
 * but WITHOUT ANY WARRANTY; without even the implied warranty 
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  
 * See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. 
 * If not, see <http://www.gnu.org/licenses/>.
 * 
 * Contributors:
 *     Daniel Murygin <dm[at]sernet[dot]de> - initial API and implementation
 ******************************************************************************/
package sernet.verinice.rcp;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.log4j.Logger;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnViewerEditor;
import org.eclipse.jface.viewers.ColumnViewerEditorActivationEvent;
import org.eclipse.jface.viewers.ColumnViewerEditorActivationStrategy;
import org.eclipse.jface.viewers.FocusCellOwnerDrawHighlighter;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.TableViewerEditor;
import org.eclipse.jface.viewers.TableViewerFocusCellManager;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;

import sernet.gs.service.CollectionUtil;
import sernet.gs.ui.rcp.main.Activator;
import sernet.verinice.iso27k.rcp.ComboModel;

/**
 * @author Daniel Murygin <dm[at]sernet[dot]de>
 */
public class IconSelectDialog extends Dialog {

    private static final Logger LOG = Logger.getLogger(IconSelectDialog.class);

    public static final String ICON_DIRECTORY = "tree-icons"; //$NON-NLS-1$

    private static final DirectoryStream.Filter<Path> ICON_FILE_FILTER = new IconFileFilter();

    private static final int SIZE_Y = 370;
    private static final int SIZE_X = 485;
    private static final int NUMBER_OF_COLUMNS = 10;
    private static final int ICON_SPACING = 10;
    private static final int THUMBNAIL_SIZE = 20;

    private Combo dirCombo;
    private ComboModel<IconPathDescriptor> dirComboModel;

    private TableViewer viewer;

    private IconPathDescriptor directory;

    private String selectedPath;

    private boolean defaultIcon = false;

    protected IconSelectDialog(Shell parentShell) {
        super(parentShell);
        initComboValues();
    }

    private void initComboValues() {
        dirComboModel = new ComboModel<>(IconPathDescriptor::getName);
        URL[] inconUrlArray = FileLocator.findEntries(Platform.getBundle(Activator.PLUGIN_ID),
                new org.eclipse.core.runtime.Path(ICON_DIRECTORY), null);

        try {
            for (URL inconUrl : inconUrlArray) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Icon dir: " + inconUrl); //$NON-NLS-1$
                }

                URL realFileUrl = FileLocator.toFileURL(inconUrl);
                String urlString = realFileUrl.toExternalForm();
                urlString = urlString.replace(" ", "%20");
                File baseDir = new File(URI.create(urlString));
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Icon dir (file system): " + baseDir.getPath()); //$NON-NLS-1$
                }
                String[] directories = baseDir.list(DirectoryFileFilter.INSTANCE);
                for (String dir : directories) {
                    if (!dir.startsWith(".")) { //$NON-NLS-1$
                        dirComboModel.add(new IconPathDescriptor(dir,
                                baseDir.getPath() + File.separator + dir));
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Error while reading icon directory: " + ICON_DIRECTORY, e); //$NON-NLS-1$
            return;
        }

        if (!dirComboModel.isEmpty()) {
            dirComboModel.sort();
        }
    }

    public void showComboValues() {
        dirCombo.setItems(dirComboModel.getLabelArray());
        if (dirComboModel.isEmpty()) {
            dirCombo.setEnabled(false);
        }
    }

    /*
     * @see
     * org.eclipse.jface.dialogs.Dialog#createDialogArea(org.eclipse.swt.widgets
     * .Composite)
     */
    @Override
    protected Control createDialogArea(Composite parent) {

        final int gridDataSizeSubtrahend = 20;

        Composite comp = (Composite) super.createDialogArea(parent);

        Label dirLabel = new Label(comp, SWT.NONE);
        dirLabel.setText(Messages.IconSelectDialog_5);
        dirCombo = new Combo(comp, SWT.VERTICAL | SWT.DROP_DOWN | SWT.BORDER | SWT.READ_ONLY);
        GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false);
        dirCombo.setLayoutData(gd);
        dirCombo.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                dirComboModel.setSelectedIndex(dirCombo.getSelectionIndex());
                directory = dirComboModel.getSelectedObject();
                loadIcons(directory.getPath());
            }
        });
        showComboValues();

        Group group = new Group(comp, SWT.NONE);
        group.setText(Messages.IconSelectDialog_6);
        GridLayout groupOrganizationLayout = new GridLayout(1, true);
        group.setLayout(groupOrganizationLayout);
        gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.minimumWidth = SIZE_Y - gridDataSizeSubtrahend;
        gd.heightHint = SIZE_X - gridDataSizeSubtrahend;
        group.setLayoutData(gd);

        createTable(group);

        group.layout();

        final Button defaultCheckbox = new Button(comp, SWT.CHECK);
        defaultCheckbox.setText(Messages.IconSelectDialog_7);
        defaultCheckbox.setSelection(false);
        defaultCheckbox.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                defaultIcon = defaultCheckbox.isEnabled();
            }
        });

        comp.pack();
        return comp;
    }

    private void loadIcons(String path) {
        Path internalDirectory = Paths.get(path);
        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(internalDirectory,
                ICON_FILE_FILTER)) {
            Stream<Path> icons = StreamSupport.stream(directoryStream.spliterator(), false);
            List<Path> iconsAsList = icons.sorted().collect(Collectors.toList());
            Collection<List<Path>> iconsInChunks = CollectionUtil.partition(iconsAsList,
                    NUMBER_OF_COLUMNS);
            viewer.setInput(iconsInChunks);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load icons from " + path, e);
        }
    }

    private void createTable(Composite parent) {

        final int gdHeightSubtrahend = 100;
        final int iconRowSize = 10;

        int style = SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION;
        viewer = new TableViewer(parent, style);
        viewer.setContentProvider(new ArrayContentProvider());

        TableViewerFocusCellManager focusCellManager = new TableViewerFocusCellManager(viewer,
                new FocusCellOwnerDrawHighlighter(viewer));
        ColumnViewerEditorActivationStrategy actSupport = new ColumnViewerEditorActivationStrategy(
                viewer) {
            @Override
            protected boolean isEditorActivationEvent(ColumnViewerEditorActivationEvent event) {
                boolean retVal = event.eventType == ColumnViewerEditorActivationEvent.TRAVERSAL
                        || event.eventType == ColumnViewerEditorActivationEvent.MOUSE_DOUBLE_CLICK_SELECTION;
                retVal = retVal || (event.eventType == ColumnViewerEditorActivationEvent.KEY_PRESSED
                        && event.keyCode == SWT.CR);
                return retVal || event.eventType == ColumnViewerEditorActivationEvent.PROGRAMMATIC;
            }
        };

        TableViewerEditor.create(viewer, focusCellManager, actSupport,
                ColumnViewerEditor.TABBING_HORIZONTAL
                        | ColumnViewerEditor.TABBING_MOVE_TO_ROW_NEIGHBOR
                        | ColumnViewerEditor.TABBING_VERTICAL
                        | ColumnViewerEditor.KEYBOARD_ACTIVATION);

        Table table = viewer.getTable();
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.heightHint = SIZE_X - gdHeightSubtrahend;
        table.setLayoutData(gd);

        table.addListener(SWT.MeasureItem,
                event -> event.height = getThumbnailSize() + ICON_SPACING);

        viewer.addSelectionChangedListener(event -> {
            ViewerCell cell = focusCellManager.getFocusCell();
            if (cell != null) {
                selectedPath = getRelativePath(
                        ((List<Path>) cell.getElement()).get(cell.getColumnIndex()).toString());
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Icon: " + selectedPath); //$NON-NLS-1$
                }
            }
        });

        for (int i = 0; i < iconRowSize; i++) {
            TableViewerColumn imageColumn = new TableViewerColumn(viewer, SWT.LEFT);
            imageColumn.setLabelProvider(new IconCellProvider(i));
            imageColumn.getColumn().setWidth(getThumbnailSize() + ICON_SPACING);
        }

        if (!dirComboModel.isEmpty()) {
            dirComboModel.setSelectedIndex(2);
            dirCombo.select(2);
            directory = dirComboModel.getSelectedObject();
            loadIcons(directory.getPath());
        } else {
            loadIcons(ICON_DIRECTORY + "silk"); //$NON-NLS-1$
        }
    }

    protected String getRelativePath(String path) {
        String relative = path;
        if (path.contains(ICON_DIRECTORY)) {
            relative = path.substring(path.indexOf(ICON_DIRECTORY));
        }
        if (relative.contains("\\")) { //$NON-NLS-1$
            relative = relative.replace('\\', '/');
        }
        return relative;
    }

    protected int getThumbnailSize() {
        return THUMBNAIL_SIZE;
    }

    public String getSelectedPath() {
        return selectedPath;
    }

    public boolean isDefaultIcon() {
        return defaultIcon;
    }

    public boolean isSomethingSelected() {
        return defaultIcon || getSelectedPath() != null;
    }

    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        newShell.setText(Messages.IconSelectDialog_11);
        newShell.setSize(SIZE_Y, SIZE_X);

        // open the window right under the mouse pointer:
        Point cursorLocation = Display.getCurrent().getCursorLocation();
        newShell.setLocation(
                new Point(cursorLocation.x - SIZE_X / 2, cursorLocation.y - SIZE_Y / 2));

    }
}

class IconFileFilter implements Filter<Path> {

    @Override
    public boolean accept(Path path) {
        String filename = path.getFileName().toString().toLowerCase();
        return filename.endsWith("gif") || filename.endsWith("png"); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
