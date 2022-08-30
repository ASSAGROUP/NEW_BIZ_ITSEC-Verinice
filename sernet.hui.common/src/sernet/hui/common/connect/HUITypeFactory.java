/*******************************************************************************
 * Copyright (c) 2009 Alexander Koderman <ak[at]sernet[dot]de>.
 * This program is free software: you can redistribute it and/or 
 * modify it under the terms of the GNU Lesser General Public License 
 * as published by the Free Software Foundation, either version 3 
 * of the License, or (at your option) any later version.
 *     This program is distributed in the hope that it will be useful,    
 * but WITHOUT ANY WARRANTY; without even the implied warranty 
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  
 * See the GNU Lesser General Public License for more details.
 *     You should have received a copy of the GNU Lesser General Public 
 * License along with this program. 
 * If not, see <http://www.gnu.org/licenses/>.
 * 
 * Contributors:
 *     Alexander Koderman <ak[at]sernet[dot]de> - initial API and implementation
 ******************************************************************************/
package sernet.hui.common.connect;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.log4j.Logger;
import org.springframework.core.io.Resource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import sernet.hui.common.VeriniceContext;
import sernet.hui.common.rules.IFillRule;
import sernet.hui.common.rules.IValidationRule;
import sernet.hui.common.rules.NotEmpty;
import sernet.hui.common.rules.RuleFactory;
import sernet.snutils.DBException;

/**
 * Parses XML file with defined properties and creates appropriate
 * <code>PropertyType </code> objects.
 * 
 */
public class HUITypeFactory {
    private static final Logger LOG = Logger.getLogger(HUITypeFactory.class);

    public static final String HUI_CONFIGURATION_FILE = "SNCA.xml";

    private static final String ATTRIBUTE_NAME = "name";
    private static final String ATTRIBUTE_ID = "id";
    private static final String ATTRIBUTE_REVERSENAME = "reversename";
    private static final String ATTRIBUTE_TOOLTIP = "tooltip";
    private static final String ATTRIBUTE_TAGS = "tags";
    private static final String ATTRIBUTE_DEFAULTVALUE = "defaultValue";
    private static final String ATTRIBUTE_REQUIRED = "required";
    private static final String ATTRIBUTE_VALUE = "value";
    private static final String BOOLEAN_TRUE = "true";
    private static final String HUI_PROPERTY = "huiproperty";
    private static final String HUI_PROPERTY_GROUP = "huipropertygroup";
    private static final String HUI_RELATION = "huirelation";

    private Document doc;

    private Set<String> allTags = new HashSet<>();

    private Map<String, EntityType> allEntities = null;

    private Map<String, String> defaultMessages = new HashMap<>();

    // loads translated messages for HUI entities from resource bundles
    private SNCAMessages messages;

    protected HUITypeFactory() {
        // Intentionally do nothing (is for the Functionless subclass).
    }

    public HUITypeFactory(Resource resource) throws DBException, IOException {
        this(resource.getURL());
    }

    public HUITypeFactory(IUrlLoader urlLoader) throws DBException {
        init(urlLoader.load());
    }

    /**
     * Create new validating parser with schema support.
     * 
     * @throws DBException
     */
    private HUITypeFactory(URL xmlFile) throws DBException {
        init(xmlFile);
    }

    private void init(URL xmlFile) throws DBException {
        if (xmlFile == null) {
            throw new DBException(
                    "Pfad für XML Systemdefinition nicht initialisiert. Config File korrekt?");
        }
        if (xmlFile.getProtocol().equals("http") || xmlFile.getProtocol().equals("ftp")) {
            try {
                xmlFile = new URL(xmlFile.toString() + "?nocache=" + Math.random());
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }

        messages = new SNCAMessages(xmlFile.toExternalForm());

        if (LOG.isDebugEnabled()) {
            LOG.debug("Conf url: " + messages.getBaseUrl() + ", localized name of huientity role: "
                    + messages.getString("role"));
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setValidating(true);
        DocumentBuilder parser = null;

        // uncomment this to enable validating of the schema:
        try {
            factory.setFeature("http://xml.org/sax/features/validation", true);
            factory.setFeature("http://apache.org/xml/features/validation/schema", true);

            factory.setAttribute("http://java.sun.com/xml/jaxp/properties/schemaLanguage",
                    "http://www.w3.org/2001/XMLSchema");
            factory.setAttribute("http://java.sun.com/xml/jaxp/properties/schemaSource",
                    getClass().getResource("/hitro.xsd").toString());

            parser = factory.newDocumentBuilder();

        } catch (ParserConfigurationException e) {
            LOG.error("Unrecognized parser feature.", e);
            throw new RuntimeException(e);
        }

        try {
            LOG.debug("Getting XML property definition from " + xmlFile);
            parser.setErrorHandler(new ErrorHandler() {
                @Override
                public void error(SAXParseException exception) throws SAXException {
                    throw new RuntimeException(exception);
                }

                @Override
                public void fatalError(SAXParseException exception) throws SAXException {
                    throw new RuntimeException(exception);
                }

                @Override
                public void warning(SAXParseException exception) throws SAXException {
                    Logger.getLogger(this.getClass())
                            .debug("Parser warning: " + exception.getLocalizedMessage());
                }
            });
            try (InputStream is = new BufferedInputStream(xmlFile.openStream())) {
                doc = parser.parse(is);
            }
            readAllEntities();

        } catch (IOException ie) {
            LOG.error(ie);
            throw new DBException(
                    "Die XML-Datei mit der Definition der Formularfelder konnte nicht geladen werden! Bitte Pfad und Erreichbarkeit laut Konfigurationsfile überprüfen.",
                    ie);
        } catch (SAXException e) {
            throw new DBException("Die XML-Datei mit der Definition der Formularfelder ist defekt!",
                    e);
        }
    }

    public static HUITypeFactory createInstance(URL xmlUrl) throws DBException {
        return new HUITypeFactory(xmlUrl);
    }

    public static HUITypeFactory getInstance() {
        return (HUITypeFactory) VeriniceContext.get(VeriniceContext.HUI_TYPE_FACTORY);
    }

    private void readAllEntities() {
        NodeList entities = doc.getElementsByTagName("huientity");
        int numberOfEntities = entities.getLength();
        this.allEntities = new HashMap<>(numberOfEntities);
        for (int i = 0; i < numberOfEntities; ++i) {

            Element entityEl = (Element) entities.item(i);
            EntityType entityObj = new EntityType();
            String id = entityEl.getAttribute(ATTRIBUTE_ID);
            entityObj.setId(id);

            // labels are loaded from SNCAMessages (resource bundles)
            String name = entityEl.getAttribute(ATTRIBUTE_NAME);
            defaultMessages.put(id, name);
            entityObj.setName(getMessage(id));

            this.allEntities.put(entityEl.getAttribute(ATTRIBUTE_ID), entityObj);

            readChildElements(entityObj, null);
        }

    }

    public Set<String> getAllTypeIds() {
        return allEntities.keySet();
    }

    public EntityType getEntityType(String id) {
        return this.allEntities.get(id);
    }

    public Collection<EntityType> getAllEntityTypes() {
        return this.allEntities.values();

    }

    public List<PropertyType> getURLPropertyTypes() {
        List<PropertyType> result = new ArrayList<>();
        Set<Entry<String, EntityType>> entrySet = allEntities.entrySet();
        for (Entry<String, EntityType> entry : entrySet) {
            List<PropertyType> types = entry.getValue().getPropertyTypes();
            for (PropertyType propertyType : types) {
                if (propertyType.isURL()) {
                    result.add(propertyType);
                }
            }
        }
        return result;
    }

    private void readChildElements(EntityType entityType, PropertyGroup propGroup) {
        NodeList nodes;
        if (propGroup != null) {
            Element groupEl = doc.getElementById(propGroup.getId());
            nodes = groupEl.getChildNodes();
        } else {
            Element entityEl = doc.getElementById(entityType.getId());
            if (entityEl == null) {
                throw new IllegalArgumentException(
                        "EntityType not found in XML definition: " + entityType.getId());
            }
            nodes = entityEl.getChildNodes();
        }

        for (int i = 0; i < nodes.getLength(); ++i) {
            if (nodes.item(i) instanceof Element) {
                readElementNode(entityType, propGroup, nodes, i);
            }
        }
    }

    private void readElementNode(EntityType entityType, PropertyGroup propGroup, NodeList nodes,
            int i) {
        Element child = (Element) nodes.item(i);
        if (child.getTagName().equals(HUI_PROPERTY)) {
            PropertyType type = readPropertyType(child.getAttribute(ATTRIBUTE_ID));
            if (propGroup != null) {
                propGroup.addPropertyType(type);
            } else {
                entityType.addPropertyType(type);
            }
        } else if (child.getTagName().equals(HUI_PROPERTY_GROUP)) {
            PropertyGroup group = readPropertyGroup(child.getAttribute(ATTRIBUTE_ID));
            entityType.addPropertyGroup(group);
            readChildElements(entityType, group);
        } else if (child.getTagName().equals(HUI_RELATION)) {
            HuiRelation relation = new HuiRelation(child.getAttribute(ATTRIBUTE_ID));
            readRelation(child, entityType.getId(), relation);
            entityType.addRelation(relation);
        }
    }

    private void readRelation(Element child, String sourceTypeId, HuiRelation relation) {
        final String id = child.getAttribute(ATTRIBUTE_ID);
        // name, reversename and tooltip are loaded from SNCAMessages (resource
        // bundles)
        // key is: [id]_name, [id]_reversename, [id]_tooltip
        final String keyName = getKey(id, ATTRIBUTE_NAME);
        final String name = child.getAttribute(ATTRIBUTE_NAME);
        final String keyReversename = getKey(id, ATTRIBUTE_REVERSENAME);
        final String reverseName = child.getAttribute(ATTRIBUTE_REVERSENAME);
        defaultMessages.put(keyName, name);
        defaultMessages.put(keyReversename, reverseName);
        relation.setName(getMessage(keyName));
        relation.setReversename(getMessage(keyReversename));
        relation.setTooltip(getMessage(getKey(id, ATTRIBUTE_TOOLTIP),
                child.getAttribute(ATTRIBUTE_TOOLTIP), true));

        relation.setTo(child.getAttribute("to"));
        relation.setFrom(sourceTypeId);
    }

    /**
     * Returns the key of a resource bundle property for a given id and
     * attribute name.
     * 
     * @param id
     *            the id of an hui element
     * @param attribute
     *            an attribute name
     * @return the key of a resource bundle property
     */
    private String getKey(String id, String attribute) {
        return (new StringBuilder(id)).append("_").append(attribute).toString();
    }

    private PropertyType readPropertyType(String id) {
        Element prop = doc.getElementById(id);
        if (prop == null) {
            return null;
        }

        PropertyType propObj = new PropertyType();
        propObj.setId(id);

        // name and tooltip are loaded from SNCAMessages (resource bundles)
        // key is: [id]_name, [id]_tooltip
        final String name = prop.getAttribute(ATTRIBUTE_NAME);
        defaultMessages.put(id, name);
        propObj.setName(getMessage(id));
        propObj.setTooltiptext(getMessage(getKey(id, ATTRIBUTE_TOOLTIP),
                prop.getAttribute(ATTRIBUTE_TOOLTIP), true));

        propObj.setTags(prop.getAttribute(ATTRIBUTE_TAGS));
        addToTagList(prop.getAttribute(ATTRIBUTE_TAGS));

        propObj.setInputType(prop.getAttribute("inputtype"));
        propObj.setCrudButtons(prop.getAttribute("crudButtons").equals(BOOLEAN_TRUE));
        propObj.setRequired(prop.getAttribute(ATTRIBUTE_REQUIRED).equals(BOOLEAN_TRUE));
        propObj.setInitialFocus(prop.getAttribute("focus").equals(BOOLEAN_TRUE));
        propObj.setEditable(prop.getAttribute("editable").equals(BOOLEAN_TRUE));
        propObj.setVisible(prop.getAttribute("visible").equals(BOOLEAN_TRUE));
        propObj.setShowInObjectBrowser(
                prop.getAttribute("showInObjectBrowser").equals(BOOLEAN_TRUE));
        propObj.setURL(prop.getAttribute("isURL").equals(BOOLEAN_TRUE));
        propObj.setReportable(prop.getAttribute("report").equals(BOOLEAN_TRUE));
        propObj.setTextRows(prop.getAttribute("textrows"));
        propObj.setReferencedEntityType(readReferencedEntityId(prop));
        propObj.setReferencedCnaLinkType(readReferencedCnaLinkType(prop));
        // read options for property
        propObj.setPredefinedValues(this.getOptionsForPropertyType(id));
        propObj.setDependencies(readDependencies(prop));

        if (propObj.isNumericSelect()) {
            propObj.setNumericMin(prop.getAttribute("min"));
            propObj.setNumericMax(prop.getAttribute("max"));
            propObj.setNumericDefault(prop.getAttribute(ATTRIBUTE_DEFAULTVALUE));
        }

        if (propObj.isBooleanSelect()) {
            propObj.setNumericMin("0");
            propObj.setNumericMax("1");
            propObj.setNumericDefault(prop.getAttribute(ATTRIBUTE_DEFAULTVALUE));
        }

        // the shortcut to set a "NotEmpty" validator:
        if (prop.getAttribute(ATTRIBUTE_REQUIRED).equals(BOOLEAN_TRUE)) {
            propObj.addValidator(new NotEmpty());
        }
        // add additional validations
        for (IValidationRule rule : readValidationRules(prop)) {
            propObj.addValidator(rule);
        }

        propObj.setDefaultRule(readDefaultRule(prop));

        return propObj;
    }

    private String readReferencedCnaLinkType(Element prop) {
        NodeList list = prop.getElementsByTagName("references");
        if (list != null && list.getLength() > 0) {
            Element referencesElmt = (Element) list.item(0);
            return referencesElmt.getAttribute("linkType");
        } else {

            return "";
        }
    }

    private String readReferencedEntityId(Element prop) {
        NodeList list = prop.getElementsByTagName("references");
        if (list != null && list.getLength() > 0) {
            Element referencesElmt = (Element) list.item(0);
            return referencesElmt.getAttribute("entitytype");
        } else {

            return "";
        }
    }

    private void addToTagList(String tags) {
        if (tags == null || tags.length() < 1) {
            return;
        }
        String newTags = tags.replaceAll("\\s+", "");
        String[] individualTags = newTags.split(",");
        allTags.addAll(Arrays.asList(individualTags));
    }

    public Set<String> getAllTags() {
        return allTags;
    }

    private PropertyGroup readPropertyGroup(String id) {
        Element group = doc.getElementById(id);
        if (group == null) {
            return null;
        }
        PropertyGroup groupObj = new PropertyGroup();
        groupObj.setId(id);
        final String name = group.getAttribute(ATTRIBUTE_NAME);
        defaultMessages.put(id, name);
        groupObj.setName(getMessage(id));
        groupObj.setTags(group.getAttribute(ATTRIBUTE_TAGS));
        addToTagList(group.getAttribute(ATTRIBUTE_TAGS));

        groupObj.setDependencies(readDependencies(group));
        return groupObj;
    }

    private Set<DependsType> readDependencies(Element prop) {
        Set<DependsType> depends = new HashSet<>();
        NodeList nodes = prop.getChildNodes();
        for (int i = 0; i < nodes.getLength(); ++i) {
            if (nodes.item(i) instanceof Element) {
                Element child = (Element) nodes.item(i);
                if ("depends".equals(child.getTagName())) {
                    String option = child.getAttribute("option");
                    String value = child.getAttribute(ATTRIBUTE_VALUE);
                    boolean inverse = Boolean.TRUE.toString().equals(child.getAttribute("inverse"));
                    depends.add(new DependsType(option, value, inverse));
                }
            }
        }
        return depends;
    }

    private List<IValidationRule> readValidationRules(Element prop) {
        ArrayList<IValidationRule> ruleList = new ArrayList<>(0);
        NodeList list = prop.getElementsByTagName("validationRule");
        for (int i = 0; i < list.getLength(); i++) {
            Element ruleElement = (Element) list.item(i);
            String className = ruleElement.getAttribute("class");
            IValidationRule rule = RuleFactory.getValidationRule(className);
            String hint = ruleElement.getAttribute("hint");
            rule.init(readValidationRuleParams(ruleElement), hint);
            ruleList.add(rule);
        }
        return ruleList;
    }

    private String[] readValidationRuleParams(Element ruleElement) {
        NodeList paramNodeList = ruleElement.getElementsByTagName("param");
        String[] params = new String[paramNodeList.getLength()];
        for (int i = 0; i < paramNodeList.getLength(); i++) {
            Element paramNode = (Element) paramNodeList.item(i);
            params[i] = paramNode.getTextContent();
        }
        return params;
    }

    private IFillRule readDefaultRule(Element prop) {
        IFillRule rule = null;
        NodeList list = prop.getElementsByTagName("defaultRule");
        for (int i = 0; i < list.getLength(); ++i) {

            Element ruleElmt = (Element) list.item(i);
            String className = ruleElmt.getAttribute("class");
            rule = RuleFactory.getDefaultRule(className);

            if (rule != null) {
                String[] params = readRuleParams(ruleElmt, rule);
                rule.init(params);

            }
        }
        return rule;
    }

    private String[] readRuleParams(Element ruleElmt, IFillRule rule) {
        NodeList paramNodeList = ruleElmt.getElementsByTagName("param");
        String[] params = new String[paramNodeList.getLength()];
        for (int i = 0; i < paramNodeList.getLength(); i++) {
            Element paramNode = (Element) paramNodeList.item(i);
            params[i] = paramNode.getTextContent();
            if (rule.isMultiLanguage()) {
                params[i] = getMessage(paramNode.getAttribute(ATTRIBUTE_ID), params[i]);
            }
        }
        return params;
    }

    private List<PropertyOption> getOptionsForPropertyType(String id) {
        Element prop = doc.getElementById(id);
        NodeList values = prop.getElementsByTagName("option");
        ArrayList<PropertyOption> possibleValues = new ArrayList<>(values.getLength());
        for (int i = 0; i < values.getLength(); ++i) {
            Element value = (Element) values.item(i);
            PropertyOption dv = new PropertyOption();
            final String idOption = value.getAttribute(ATTRIBUTE_ID);
            dv.setId(idOption);
            // name is loaded from SNCAMessages (resource bundles)
            dv.setName(getMessage(idOption, value.getAttribute(ATTRIBUTE_NAME)));

            String optValue = value.getAttribute(ATTRIBUTE_VALUE);
            if (optValue != null && !optValue.isEmpty()) {
                try {
                    dv.setValue(Integer.parseInt(optValue));
                } catch (NumberFormatException e) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Not a valid number for option " + optValue, e);
                    }
                }
            }

            possibleValues.add(dv);
        }
        return possibleValues;
    }

    public PropertyType getPropertyType(String entityTypeID, String id) {
        return allEntities.get(entityTypeID).getPropertyType(id);
    }

    /**
     * Get list of possible relations from one entity type to another entity
     * type. I.e. from person to document: author, reviewer, signer
     * 
     * @param fromEntityTypeID
     * @param toEntityTypeID
     * @return
     */
    public Set<HuiRelation> getPossibleRelations(String fromEntityTypeID, String toEntityTypeID) {
        return getEntityType(fromEntityTypeID).getPossibleRelations(toEntityTypeID);
    }

    /**
     * Get list of possible relations from the given entity type to any other
     * entities. I.e. from "document" - to person: author, reviewer, signer - to
     * server: documentation - to requirement: contract
     * 
     * @param fromEntityTypeID
     * @return
     */
    public Set<HuiRelation> getPossibleRelationsFrom(String fromEntityTypeID) {

        if (getEntityType(fromEntityTypeID) == null) {
            LOG.error("cannot find entitytype for:\t" + fromEntityTypeID);

        }

        return getEntityType(fromEntityTypeID).getPossibleRelations();
    }

    /**
     * Get list of all possible relations from any entity type to the given
     * entity type. I.e. to "requirement" - from document: contract - from
     * person: responsible - from control: implementation
     * 
     * @param toEntityTypeID
     * @return
     */
    public Set<HuiRelation> getPossibleRelationsTo(String toEntityTypeID) {
        // for this reverse request we have to iterate through all entitytypes
        // and search:
        Set<HuiRelation> allRelations = new HashSet<>();
        Set<Entry<String, EntityType>> entrySet = allEntities.entrySet();
        for (Entry<String, EntityType> entry : entrySet) {
            EntityType entityType = entry.getValue();
            Set<HuiRelation> theseRelations = entityType.getPossibleRelations(toEntityTypeID);
            if (theseRelations != null && !theseRelations.isEmpty()) {
                allRelations.addAll(theseRelations);
            }
        }
        return allRelations;
    }

    /**
     * Returns the HuiRelation object with the ID = huiRelationId.
     * 
     * @param huiRelationId
     *            - id of the HuiRelation object to be returned.
     * @return the HuiRelation object to the given huiRelationId.
     */
    public HuiRelation getRelation(String huiRelationId) {
        if (allEntities == null) {
            LOG.debug("No entities in HUITypeFactory!! Instance: " + this);
            return null;
        }

        Set<Entry<String, EntityType>> entrySet = allEntities.entrySet();
        for (Entry<String, EntityType> entry : entrySet) {
            EntityType entityType = entry.getValue();
            HuiRelation possibleRelation = entityType.getPossibleRelation(huiRelationId);
            if (possibleRelation != null) {
                return possibleRelation;
            }
        }
        return null;
    }

    /**
     * Returns a translated message for a key if no translated message is found,
     * "[key] (!)" is returned
     * 
     * Translated messages are read from {@link SNCAMessages} which are special
     * resource bundles.
     * 
     * @param key
     *            key of the message
     * @return a translated message or (if not found) "[key] (!)"
     */
    public String getMessage(String key) {
        return getMessage(key, defaultMessages.get(key), false);
    }

    /**
     * Returns a translated message for a key or a default message if no
     * translated message is found or "[key] (!)" if a default message is not
     * found
     * 
     * Translated messages are read from {@link SNCAMessages} which are special
     * resource bundles.
     * 
     * @param key
     *            key of the message
     * @param defaultMessage
     *            default message
     * @return a translated message or a default message if not found or "[key]
     *         (!)" if a default message is not found
     */
    public String getMessage(String key, String defaultMessage) {
        return getMessage(key, defaultMessage, false);
    }

    /**
     * Returns a translated message for a key or a default message if no
     * translated message is found or "[key] (!)" if a default message is not
     * found and emptyIfNotFound is false or "" if a default message is not
     * found and emptyIfNotFound is true
     * 
     * Translated messages are read from {@link SNCAMessages} which are special
     * resource bundles.
     * 
     * @param key
     *            key of the message
     * @param defaultMessage
     *            default message
     * @param emptyIfNotFound
     *            true: an empty string is returned if translated message is not
     *            found and defaultMessage is null or empty false: null is
     *            returned if translated message is not found and defaultMessage
     *            is null or empty
     * @return a translated message or a default message if not found or "[key]
     *         (!)" if a default message is not found or "" if a default message
     *         is not found and emptyIfNotFound is true
     */
    public String getMessage(String key, String defaultMessage, boolean emptyIfNotFound) {
        // treat an empty string as null
        String message = messages.getString(key);
        if (message != null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("returning translated message: " + message + ", key: " + key);
            }
            return message;
        } else {
            return getDefaultMessage(key, defaultMessage, emptyIfNotFound);
        }
    }

    private String getDefaultMessage(String key, String defaultMessage, boolean emptyIfNotFound) {

        if (defaultMessage == null || defaultMessage.isEmpty()) {
            if (emptyIfNotFound) {
                // message is optional may be empty
                if (LOG.isDebugEnabled()) {
                    LOG.debug("SNCA message not found, key is: " + key);
                }
                return "";
            } else {
                if (!"".equals(key)) {
                    LOG.warn("SNCA message not found, key is: " + key);
                } else {
                    LOG.info("key is empty String");
                }
                return key + " (!)";
            }
        } else {

            defaultMessages.put(key, defaultMessage);

            if (LOG.isDebugEnabled()) {
                LOG.debug("returning message from SNCA.XML: " + defaultMessage + ", key: " + key);
                // mark missing resource bundle entries
                return defaultMessage + " (SNCA.xml)";
            }
            return defaultMessage;
        }

    }

    /**
     * Returns the group the propertyId belongs to.
     * 
     * @param entityId
     *            - the id of entity the property belongs to
     * @param propertyId
     *            - the id of the property
     * @return - null if no Group is found
     */
    public PropertyGroup getPropertyGroup(String entityId, String propertyId) {

        EntityType entityType = getEntityType(entityId);
        if (entityType == null) {
            return null;
        }
        List<PropertyGroup> propertyGroups = entityType.getPropertyGroups();
        if (propertyGroups == null) {
            return null;
        }
        for (PropertyGroup group : propertyGroups) {
            if (group.getPropertyType(propertyId) != null) {
                return group;
            }
        }

        return null;

    }

}
