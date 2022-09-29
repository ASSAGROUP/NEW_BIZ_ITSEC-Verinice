/*******************************************************************************
 * Copyright (c) 2013 Daniel Murygin.
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
package sernet.verinice.interfaces;

import java.util.LinkedList;
import java.util.List;

import sernet.verinice.interfaces.graph.IGraphElementLoader;
import sernet.verinice.interfaces.graph.IGraphService;
import sernet.verinice.interfaces.graph.VeriniceGraph;

/**
 * Extend or use GraphCommand to operate with a {@link VeriniceGraph}.
 *
 * Do not override execute in a GraphCommand, use executeWithGraph instead.
 *
 * Before executing you should add {@link IGraphElementLoader} and relation ids
 * to narrow elements in the graph.
 *
 * @author Daniel Murygin <dm[at]sernet[dot]de>
 */
public class GraphCommand extends GenericCommand implements IGraphCommand {

    private static final long serialVersionUID = -6577015989775607193L;

    private VeriniceGraph graph;

    private List<IGraphElementLoader> elementLoaderList;
    private boolean loadLinks = true;
    private List<String> relationIdList;

    private transient IGraphService graphService;

    /**
     * Do not override execute in a GraphCommand, use executeWithGraph instead.
     *
     * @see sernet.verinice.interfaces.ICommand#execute()
     */
    @Override
    public void execute() {
        initGraph();
        executeWithGraph();
    }

    /**
     * Override this method to operate with the {@link VeriniceGraph}.
     *
     * @see sernet.verinice.interfaces.IGraphCommand#executeWithGraph()
     */
    @Override
    public void executeWithGraph() {
        // override this method
    }

    protected void initGraph() {
        if (loadLinks) {
            String[] relationIds = null;
            if (relationIdList != null && !relationIdList.isEmpty()) {
                relationIds = relationIdList.toArray(new String[relationIdList.size()]);
            }
            this.graph = getGraphService().create(getLoader(), relationIds);
        } else {
            this.graph = getGraphService().create(getLoader(), false);
        }
    }

    @Override
    public List<IGraphElementLoader> getLoader() {
        if (elementLoaderList == null) {
            elementLoaderList = new LinkedList<>();
        }
        return elementLoaderList;
    }

    @Override
    public void setLoader(List<IGraphElementLoader> loader) {
        this.elementLoaderList = loader;
    }

    @Override
    public void addLoader(IGraphElementLoader loader) {
        getLoader().add(loader);
    }

    protected void setLoadLinks(boolean loadLinks) {
        this.loadLinks = loadLinks;
    }

    @Override
    public List<String> getRelationIds() {
        if (relationIdList == null) {
            relationIdList = new LinkedList<>();
        }
        return relationIdList;
    }

    @Override
    public void setRelationIds(List<String> relationIdList) {
        this.relationIdList = relationIdList;
    }

    @Override
    public void addRelationId(String id) {
        getRelationIds().add(id);
    }

    public VeriniceGraph getGraph() {
        return graph;
    }

    public void setGraph(VeriniceGraph graph) {
        this.graph = graph;
    }

    public IGraphService getGraphService() {
        return graphService;
    }

    @Override
    public void setGraphService(IGraphService graphService) {
        this.graphService = graphService;
    }
}
