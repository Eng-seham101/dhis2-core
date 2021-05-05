/*
 * Copyright (c) 2004-2021, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * Neither the name of the HISP project nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.hisp.dhis.gist;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static org.hisp.dhis.gist.GistLogic.getBaseType;
import static org.hisp.dhis.gist.GistLogic.isCollectionSizeFilter;
import static org.hisp.dhis.gist.GistLogic.isHrefProperty;
import static org.hisp.dhis.gist.GistLogic.isNonNestedPath;
import static org.hisp.dhis.gist.GistLogic.isPersistentCollectionField;
import static org.hisp.dhis.gist.GistLogic.isPersistentReferenceField;
import static org.hisp.dhis.gist.GistLogic.isStringLengthFilter;
import static org.hisp.dhis.gist.GistLogic.parentPath;
import static org.hisp.dhis.gist.GistLogic.pathOnSameParent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;

import org.hisp.dhis.gist.GistQuery.Comparison;
import org.hisp.dhis.gist.GistQuery.Field;
import org.hisp.dhis.gist.GistQuery.Filter;
import org.hisp.dhis.gist.GistQuery.Owner;
import org.hisp.dhis.hibernate.jsonb.type.JsonbFunctions;
import org.hisp.dhis.period.Period;
import org.hisp.dhis.period.PeriodType;
import org.hisp.dhis.query.JpaQueryUtils;
import org.hisp.dhis.schema.Property;
import org.hisp.dhis.schema.RelativePropertyContext;
import org.hisp.dhis.schema.Schema;
import org.hisp.dhis.schema.annotation.Gist.Transform;
import org.hisp.dhis.security.acl.AclService;
import org.hisp.dhis.translation.Translation;
import org.hisp.dhis.user.User;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Purpose of this helper is to avoid passing around same state while building
 * the HQL query and to setup post processing of results.
 *
 * Usage:
 * <ol>
 * <li>Use {@link #buildFetchHQL()} to create the HQL query</li>
 * <li>Use {@link #transform(List)} on the result rows when querying selected
 * columns</li>
 * </ol>
 * <p>
 * Within the HQL naming conventions are:
 *
 * <pre>
 *   o => owner table
 *   e => member collection element table
 * </pre>
 *
 * @author Jan Bernitt
 */
@RequiredArgsConstructor
final class GistBuilder
{

    private static final String GIST_PATH = "/gist";

    /**
     * HQL does not allow plain "null" in select columns list as the type is
     * unknown. Therefore we just cast it to some simple type. Which is not
     * important as the value will be {@code null} anyway.
     */
    private static final String HQL_NULL = "cast(null as char)";

    private static final String TRANSLATIONS_PROPERTY = "translations";

    private static final String ID_PROPERTY = "id";

    static GistBuilder createFetchBuilder( GistQuery query, RelativePropertyContext context, User user )
    {
        return new GistBuilder( user, addSupportFields( query, context ), context );
    }

    static GistBuilder createCountBuilder( GistQuery query, RelativePropertyContext context, User user )
    {
        return new GistBuilder( user, query, context );
    }

    private final User user;

    private final GistQuery query;

    private final RelativePropertyContext context;

    private final List<Consumer<Object[]>> fieldResultTransformers = new ArrayList<>();

    private final Map<String, Integer> fieldIndexByPath = new HashMap<>();

    /**
     * Depending on what fields should be listed other fields are needed to
     * fully compute the requested fields. Such fields are added should they not
     * be present already. This is done only within the builder. While the
     * fields are fetched from the database the caller does not include the
     * added fields as it is still working with the original field list.
     */
    private static GistQuery addSupportFields( GistQuery query,
        RelativePropertyContext context )
    {
        GistQuery extended = query;
        for ( Field f : query.getFields() )
        {
            if ( Field.REFS_PATH.equals( f.getPropertyPath() ) )
            {
                continue;
            }
            Property p = context.resolveMandatory( f.getPropertyPath() );
            // ID column not present but ID column required?
            if ( (isPersistentCollectionField( p ) || isHrefProperty( p ))
                && !existsSameParentField( extended, f, ID_PROPERTY ) )
            {
                extended = extended.withField( pathOnSameParent( f.getPropertyPath(), ID_PROPERTY ) );
            }
            // translatable fields? => make sure we have translations
            if ( (query.isTranslate() || f.isTranslate()) && p.isTranslatable()
                && !existsSameParentField( extended, f, TRANSLATIONS_PROPERTY ) )
            {
                extended = extended.withField( pathOnSameParent( f.getPropertyPath(), TRANSLATIONS_PROPERTY ) );
            }
        }
        return extended;
    }

    private static boolean existsSameParentField( GistQuery query, Field field, String property )
    {
        String parentPath = parentPath( field.getPropertyPath() );
        String requiredPath = parentPath.isEmpty() ? property : parentPath + "." + property;
        return query.getFields().stream().anyMatch( f -> f.getPropertyPath().equals( requiredPath ) );
    }

    private String getMemberPath( String property )
    {
        List<Property> path = context.resolvePath( property );
        return path.size() == 1 ? path.get( 0 ).getFieldName()
            : path.stream().map( Property::getFieldName ).collect( joining( "." ) );
    }

    /*
     * SQL response post processing...
     */

    @AllArgsConstructor( access = AccessLevel.PRIVATE )
    public static final class IdObject
    {
        @JsonProperty
        final String id;
    }

    public void transform( List<Object[]> rows )
    {
        if ( fieldResultTransformers.isEmpty() )
        {
            return;
        }
        for ( Object[] row : rows )
        {
            for ( Consumer<Object[]> transformer : fieldResultTransformers )
            {
                transformer.accept( row );
            }
        }
    }

    private void addTransformer( Consumer<Object[]> transformer )
    {
        fieldResultTransformers.add( transformer );
    }

    private Object translate( Object value, String property, Object translations )
    {
        @SuppressWarnings( "unchecked" )
        Set<Translation> list = (Set<Translation>) translations;
        if ( list == null || list.isEmpty() )
        {
            return value;
        }
        String locale = query.getTranslationLocale().toString();
        for ( Translation t : list )
        {
            if ( t.getLocale().equalsIgnoreCase( locale ) && t.getProperty().equalsIgnoreCase( property )
                && !t.getValue().isEmpty() )
                return t.getValue();
        }
        String lang = query.getTranslationLocale().getLanguage();
        for ( Translation t : list )
        {
            if ( t.getLocale().startsWith( lang ) && t.getProperty().equalsIgnoreCase( property )
                && !t.getValue().isEmpty() )
                return t.getValue();
        }
        return value;
    }

    /*
     * HQL query building...
     */

    public String buildFetchHQL()
    {
        String fields = createFieldsHQL();
        String accessFilters = createAccessFilterHQL( context, "e" );
        String userFilters = createFiltersHQL();
        String orders = createOrdersHQL();
        String elementTable = query.getElementType().getSimpleName();
        Owner owner = query.getOwner();
        if ( owner == null )
        {
            return String.format( "select %s from %s e where (%s) and (%s) order by %s",
                fields, elementTable, userFilters, accessFilters, orders );
        }
        String op = query.isInverse() ? "not in" : "in";
        String ownerTable = owner.getType().getSimpleName();
        String collectionName = context.switchedTo( owner.getType() )
            .resolveMandatory( owner.getCollectionProperty() ).getFieldName();
        return String.format(
            "select %s from %s o, %s e where o.uid = :OwnerId and e %s elements(o.%s) and (%s) and (%s) order by %s",
            fields, ownerTable, elementTable, op, collectionName, userFilters, accessFilters, orders );
    }

    public String buildCountHQL()
    {
        String userFilters = createFiltersHQL();
        String accessFilters = createAccessFilterHQL( context, "e" );
        String elementTable = query.getElementType().getSimpleName();
        Owner owner = query.getOwner();
        if ( owner == null )
        {
            return String.format( "select count(*) from %s e where (%s) and (%s)", elementTable, userFilters,
                accessFilters );
        }
        String op = query.isInverse() ? "not in" : "in";
        String ownerTable = owner.getType().getSimpleName();
        String collectionName = context.switchedTo( owner.getType() )
            .resolveMandatory( owner.getCollectionProperty() ).getFieldName();
        return String.format(
            "select count(*) from %s o, %s e where o.uid = :OwnerId and e %s elements(o.%s) and (%s) and (%s)",
            ownerTable, elementTable, op, collectionName, userFilters, accessFilters );
    }

    private String createAccessFilterHQL( RelativePropertyContext context, String tableName )
    {
        if ( !isFilterBySharing( context ) )
        {
            return "1=1";
        }
        String access = JpaQueryUtils.generateSQlQueryForSharingCheck( tableName + ".sharing", user,
            AclService.LIKE_READ_METADATA );
        // HQL does not allow the ->> syntax so we have to substitute with the
        // named function: jsonb_extract_path_text
        access = access.replaceAll( tableName + "\\.sharing->>'([^']+)'",
            JsonbFunctions.EXTRACT_PATH_TEXT + "(" + tableName + ".sharing, '$1')" );
        return "(" + access + ")";
    }

    private boolean isFilterBySharing( RelativePropertyContext context )
    {
        Property sharing = context.resolve( "sharing" );
        return sharing != null && sharing.isPersisted() && user != null && !user.isSuper();
    }

    private String createFieldsHQL()
    {
        int i = 0;
        for ( Field f : query.getFields() )
        {
            fieldIndexByPath.put( f.getPropertyPath(), i++ );
        }
        return join( query.getFields(), ", ", "e", this::createFieldHQL );
    }

    private String createFieldHQL( int index, Field field )
    {
        String path = field.getPropertyPath();
        if ( Field.REFS_PATH.equals( path ) )
        {
            return HQL_NULL;
        }
        Property property = context.resolveMandatory( path );
        if ( query.isTranslate() && property.isTranslatable() && query.getTranslationLocale() != null )
        {
            int translationsFieldIndex = getSameParentFieldIndex( path, TRANSLATIONS_PROPERTY );
            addTransformer( row -> row[index] = translate( row[index], property.getTranslationKey(),
                row[translationsFieldIndex] ) );
        }
        if ( isHrefProperty( property ) )
        {
            String endpointRoot = getSameParentEndpointRoot( path );
            Integer idFieldIndex = getSameParentFieldIndex( path, ID_PROPERTY );
            if ( idFieldIndex != null && endpointRoot != null )
            {
                addTransformer( row -> row[index] = toEndpointURL( endpointRoot, row[idFieldIndex] ) );
            }
            return HQL_NULL;
        }
        if ( isPersistentReferenceField( property ) )
        {
            return createReferenceFieldHQL( index, field );
        }
        if ( isPersistentCollectionField( property ) )
        {
            return createCollectionFieldHQL( index, field );
        }
        if ( property.isCollection() && property.getOwningRole() != null )
        {
            return "size(e." + getMemberPath( path ) + ")";
        }
        String memberPath = getMemberPath( path );
        return "e." + memberPath;
    }

    private String createReferenceFieldHQL( int index, Field field )
    {
        String tableName = "t_" + index;
        String path = field.getPropertyPath();
        Property property = context.resolveMandatory( path );
        RelativePropertyContext fieldContext = context.switchedTo( property.getKlass() );
        String propertyName = determineReferenceProperty( field, fieldContext, false );
        Schema propertySchema = fieldContext.getHome();
        if ( propertyName == null || propertySchema.getRelativeApiEndpoint() == null )
        {
            // embed the object directly
            if ( !property.isRequired() )
            {
                return String.format( "(select %1$s from %2$s %1$s where %1$s = e.%3$s)",
                    tableName, property.getKlass().getSimpleName(), getMemberPath( path ) );
            }
            return "e." + getMemberPath( path );
        }

        if ( property.isIdentifiableObject() )
        {
            String endpointRoot = getEndpointRoot( property );
            if ( endpointRoot != null )
            {
                int refIndex = fieldIndexByPath.get( Field.REFS_PATH );
                addTransformer(
                    row -> addEndpointURL( row, refIndex, field, toEndpointURL( endpointRoot, row[index] ) ) );
            }
        }

        if ( field.getTransformation() == Transform.ID_OBJECTS )
        {
            addTransformer( row -> row[index] = toIdObject( row[index] ) );
        }
        if ( property.isRequired() )
        {
            return "e." + getMemberPath( path ) + "." + propertyName;
        }
        return String.format( "(select %1$s.%2$s from %3$s %1$s where %1$s = e.%4$s)",
            tableName, propertyName, property.getKlass().getSimpleName(), getMemberPath( path ) );
    }

    private String createCollectionFieldHQL( int index, Field field )
    {
        String path = field.getPropertyPath();
        Property property = context.resolveMandatory( path );
        String endpointRoot = getSameParentEndpointRoot( path );
        if ( endpointRoot != null )
        {
            int idFieldIndex = getSameParentFieldIndex( path, ID_PROPERTY );
            int refIndex = fieldIndexByPath.get( Field.REFS_PATH );
            addTransformer( row -> addEndpointURL( row, refIndex, field,
                toEndpointURL( endpointRoot, row[idFieldIndex], property ) ) );
        }

        Transform transform = field.getTransformation();
        switch ( transform )
        {
        default:
        case AUTO:
        case NONE:
            return HQL_NULL;
        case SIZE:
            return createSizeTransformerHQL( index, field, property, "" );
        case IS_EMPTY:
            return createSizeTransformerHQL( index, field, property, "=0" );
        case IS_NOT_EMPTY:
            return createSizeTransformerHQL( index, field, property, ">0" );
        case NOT_MEMBER:
            return createHasMemberTransformerHQL( index, field, property, "=0" );
        case MEMBER:
            return createHasMemberTransformerHQL( index, field, property, ">0" );
        case ID_OBJECTS:
            addTransformer( row -> row[index] = toIdObjects( row[index] ) );
            return createIdsTransformerHQL( index, field, property );
        case IDS:
            return createIdsTransformerHQL( index, field, property );
        case PLUCK:
            return createPluckTransformerHQL( index, field, property );
        }
    }

    private String createSizeTransformerHQL( int index, Field field, Property property, String compare )
    {
        String tableName = "t_" + index;
        RelativePropertyContext fieldContext = context.switchedTo( property.getItemKlass() );
        String memberPath = getMemberPath( field.getPropertyPath() );

        if ( !isFilterBySharing( fieldContext ) )
        {
            // generates better SQL in case no access control is needed
            return String.format( "size(e.%s) %s", memberPath, compare );
        }
        String accessFilter = createAccessFilterHQL( fieldContext, tableName );
        return String.format(
            "(select count(*) %5$s from %2$s %1$s where %1$s in elements(e.%3$s) and %4$s)",
            tableName, property.getItemKlass().getSimpleName(), memberPath, accessFilter, compare );
    }

    private String createIdsTransformerHQL( int index, Field field, Property property )
    {
        return createPluckTransformerHQL( index, field, property );
    }

    private String createPluckTransformerHQL( int index, Field field, Property property )
    {
        String tableName = "t_" + index;
        RelativePropertyContext itemContext = context.switchedTo( property.getItemKlass() );
        String propertyName = determineReferenceProperty( field, itemContext, true );
        if ( propertyName == null || property.getItemKlass() == Period.class )
        {
            // give up
            return createSizeTransformerHQL( index, field, property, "" );
        }
        String accessFilter = createAccessFilterHQL( itemContext, tableName );
        return String.format(
            "(select array_agg(%1$s.%2$s) from %3$s %1$s where %1$s in elements(e.%4$s) and %5$s)",
            tableName, propertyName, property.getItemKlass().getSimpleName(),
            getMemberPath( field.getPropertyPath() ), accessFilter );
    }

    private String determineReferenceProperty( Field field, RelativePropertyContext fieldContext, boolean forceTextual )
    {
        Class<?> fieldType = fieldContext.getHome().getKlass();
        if ( field.getTransformationArgument() != null )
        {
            return getPluckPropertyName( field, fieldType, forceTextual );
        }
        if ( fieldType == PeriodType.class )
        {
            // this is how HQL refers to discriminator property, here "name"
            return "class";
        }
        if ( existsAsReference( fieldContext, "id" ) )
        {
            return fieldContext.resolveMandatory( "id" ).getFieldName();
        }
        if ( existsAsReference( fieldContext, "code" ) )
        {
            return fieldContext.resolveMandatory( "code" ).getFieldName();
        }
        if ( existsAsReference( fieldContext, "name" ) )
        {
            return fieldContext.resolveMandatory( "name" ).getFieldName();
        }
        return null;
    }

    private boolean existsAsReference( RelativePropertyContext fieldContext, String id )
    {
        Property p = fieldContext.resolve( id );
        return p != null && p.isPersisted();
    }

    private String getPluckPropertyName( Field field, Class<?> ownerType, boolean forceTextual )
    {
        String propertyName = field.getTransformationArgument();
        Property property = context.switchedTo( ownerType ).resolveMandatory( propertyName );
        if ( forceTextual && property.getKlass() != String.class )
        {
            throw new UnsupportedOperationException( "Only textual properties can be plucked, but " + propertyName
                + " is a: " + property.getKlass() );
        }
        return propertyName;
    }

    private String createHasMemberTransformerHQL( int index, Field field, Property property, String compare )
    {
        String tableName = "t_" + index;
        String accessFilter = createAccessFilterHQL( context.switchedTo( property.getItemKlass() ), tableName );
        return String.format(
            "(select count(*) %6$s from %2$s %1$s where %1$s in elements(e.%3$s) and %1$s.uid = :p_%4$s and %5$s)",
            tableName, property.getItemKlass().getSimpleName(),
            getMemberPath( field.getPropertyPath() ), field.getPropertyPath(), accessFilter, compare );
    }

    @SuppressWarnings( "unchecked" )
    private void addEndpointURL( Object[] row, int refIndex, Field field, String url )
    {
        if ( url == null || url.isEmpty() )
        {
            return;
        }
        if ( row[refIndex] == null )
        {
            row[refIndex] = new TreeMap<>();
        }
        ((Map<String, String>) row[refIndex]).put( field.getName(), url );
    }

    private String toEndpointURL( String endpointRoot, Object id )
    {
        return id == null ? null : endpointRoot + '/' + id + GIST_PATH + getEndpointUrlParams();
    }

    private String toEndpointURL( String endpointRoot, Object id, Property property )
    {
        return endpointRoot + '/' + id + '/' + property.key() + GIST_PATH + getEndpointUrlParams();
    }

    private String getEndpointUrlParams()
    {
        return query.isAbsolute() ? "?absoluteUrls=true" : "";
    }

    private static IdObject toIdObject( Object id )
    {
        return id == null ? null : new IdObject( (String) id );
    }

    private static Object[] toIdObjects( Object ids )
    {
        return ids == null || ((Object[]) ids).length == 0
            ? null
            : Arrays.stream( ((String[]) ids) ).map( IdObject::new ).toArray();
    }

    private Integer getSameParentFieldIndex( String path, String translations )
    {
        return fieldIndexByPath.get( pathOnSameParent( path, translations ) );
    }

    private String getSameParentEndpointRoot( String path )
    {
        return getEndpointRoot( context.switchedTo( path ).getHome() );
    }

    private String getEndpointRoot( Property property )
    {
        return getEndpointRoot( context.switchedTo( property.getKlass() ).getHome() );
    }

    private String getEndpointRoot( Schema schema )
    {
        String relativeApiEndpoint = schema.getRelativeApiEndpoint();
        return relativeApiEndpoint == null ? null : query.getEndpointRoot() + relativeApiEndpoint;
    }

    private String createFiltersHQL()
    {
        String rootJunction = query.isAnyFilter() ? " or " : " and ";
        return join( query.getFilters(), rootJunction, "1=1", this::createFilterHQL );
    }

    private String createFilterHQL( int index, Filter filter )
    {
        if ( !isNonNestedPath( filter.getPropertyPath() ) )
        {
            List<Property> path = context.resolvePath( filter.getPropertyPath() );
            if ( isExistsInCollectionFilter( path ) )
            {
                return createExistsFilterHQL( index, filter, path );
            }
        }
        return createFilterHQL( index, filter, "e." + getMemberPath( filter.getPropertyPath() ) );
    }

    private boolean isExistsInCollectionFilter( List<Property> path )
    {
        return path.size() == 2 && isPersistentCollectionField( path.get( 0 ) )
            || path.size() == 3 && isPersistentReferenceField( path.get( 0 ) )
                && isPersistentCollectionField( path.get( 1 ) );
    }

    private String createExistsFilterHQL( int index, Filter filter, List<Property> path )
    {
        Property compared = path.get( path.size() - 1 );
        Property collection = path.get( path.size() - 2 );
        String tableName = "ft_" + index;
        String pathToCollection = path.size() == 2
            ? path.get( 0 ).getFieldName()
            : path.get( 0 ).getFieldName() + "." + path.get( 1 ).getFieldName();
        return String.format( "exists (select 1 from %2$s %1$s where %1$s in elements(e.%3$s) and %4$s)",
            tableName, collection.getItemKlass().getSimpleName(), pathToCollection,
            createFilterHQL( index, filter, tableName + "." + compared.getFieldName() ) );
    }

    private String createFilterHQL( int index, Filter filter, String field )
    {
        StringBuilder str = new StringBuilder();
        Property property = context.resolveMandatory( filter.getPropertyPath() );
        String fieldTemplate = "%s";
        if ( isStringLengthFilter( filter, property ) )
        {
            fieldTemplate = "length(%s)";
        }
        else if ( isCollectionSizeFilter( filter, property ) )
        {
            fieldTemplate = "size(%s)";
        }
        str.append( String.format( fieldTemplate, field ) );
        Comparison operator = filter.getOperator();
        str.append( " " ).append( createOperatorLeftSideHQL( operator ) );
        if ( !operator.isUnary() )
        {
            str.append( " :f_" + index ).append( createOperatorRightSideHQL( operator ) );
        }
        return str.toString();
    }

    private String createOrdersHQL()
    {
        return join( query.getOrders(), ",", "e.id asc",
            ( index, order ) -> " e." + getMemberPath( order.getPropertyPath() ) + " "
                + order.getDirection().name().toLowerCase() );
    }

    private String createOperatorLeftSideHQL( Comparison operator )
    {
        switch ( operator )
        {
        case NULL:
            return "is null";
        case NOT_NULL:
            return "is not null";
        case EQ:
            return "=";
        case NE:
            return "!=";
        case LT:
            return "<";
        case GT:
            return ">";
        case LE:
            return "<=";
        case GE:
            return ">=";
        case IN:
            return "in (";
        case NOT_IN:
            return "not in (";
        case EMPTY:
            return "= 0";
        case NOT_EMPTY:
            return "> 0";
        case LIKE:
        case STARTS_LIKE:
        case ENDS_LIKE:
        case ILIKE:
        case STARTS_WITH:
        case ENDS_WITH:
            return "like";
        case NOT_LIKE:
        case NOT_STARTS_LIKE:
        case NOT_ENDS_LIKE:
        case NOT_ILIKE:
        case NOT_STARTS_WITH:
        case NOT_ENDS_WITH:
            return "not like";
        default:
            return "";
        }
    }

    private String createOperatorRightSideHQL( Comparison operator )
    {
        switch ( operator )
        {
        case NOT_IN:
        case IN:
            return ")";
        default:
            return "";
        }
    }

    private <T> String join( Collection<T> elements, String delimiter, String empty,
        BiFunction<Integer, T, String> elementFactory )
    {
        if ( elements == null || elements.isEmpty() )
        {
            return empty;
        }
        StringBuilder str = new StringBuilder();
        int i = 0;
        for ( T e : elements )
        {
            if ( str.length() > 0 )
            {
                str.append( delimiter );
            }
            str.append( elementFactory.apply( i++, e ) );
        }
        return str.toString();
    }

    /*
     * HQL query parameter mapping...
     */

    public void addFetchParameters( BiConsumer<String, Object> dest,
        BiFunction<String, Class<?>, Object> argumentParser )
    {
        for ( Field field : query.getFields() )
        {
            if ( field.getTransformationArgument() != null && field.getTransformation() != Transform.PLUCK )
            {
                dest.accept( "p_" + field.getPropertyPath(), field.getTransformationArgument() );
            }
        }
        addCountParameters( dest, argumentParser );
    }

    public void addCountParameters( BiConsumer<String, Object> dest,
        BiFunction<String, Class<?>, Object> argumentParser )
    {
        Owner owner = query.getOwner();
        if ( owner != null )
        {
            dest.accept( "OwnerId", owner.getId() );
        }
        int i = 0;
        for ( Filter filter : query.getFilters() )
        {
            Comparison operator = filter.getOperator();
            if ( !operator.isUnary() )
            {
                Property property = context.resolveMandatory( filter.getPropertyPath() );
                Object value = getParameterValue( property, filter, argumentParser );
                dest.accept( "f_" + i, operator.isStringCompare()
                    ? completeLikeExpression( operator, (String) value )
                    : value );
            }
            i++;
        }
    }

    private Object getParameterValue( Property property, Filter filter,
        BiFunction<String, Class<?>, Object> argumentParser )
    {
        String[] value = filter.getValue();
        if ( value.length == 0 )
        {
            return "";
        }
        if ( value.length == 1 )
        {
            return getParameterValue( property, filter, value[0], argumentParser );
        }
        return stream( value ).map( e -> getParameterValue( property, filter, e, argumentParser ) ).collect( toList() );
    }

    private Object getParameterValue( Property property, Filter filter, String value,
        BiFunction<String, Class<?>, Object> argumentParser )
    {
        if ( value == null || property.getKlass() == String.class )
        {
            return value;
        }
        if ( isCollectionSizeFilter( filter, property ) )
        {
            return argumentParser.apply( value, Integer.class );
        }
        Class<?> itemType = getBaseType( property );
        return argumentParser.apply( value, itemType );
    }

    private static Object completeLikeExpression( Comparison operator, String value )
    {
        switch ( operator )
        {
        case LIKE:
        case ILIKE:
        case NOT_ILIKE:
        case NOT_LIKE:
            return sqlLikeExpressionOf( value );
        case STARTS_LIKE:
        case STARTS_WITH:
        case NOT_STARTS_LIKE:
        case NOT_STARTS_WITH:
            return value + "%";
        case ENDS_LIKE:
        case ENDS_WITH:
        case NOT_ENDS_LIKE:
        case NOT_ENDS_WITH:
            return "%" + value;
        default:
            return value;
        }
    }

    /**
     * Converts the user input of a like pattern matching to the SQL like
     * expression.
     *
     * Like (pattern matching) allows for two modes:
     *
     * 1. providing a pattern with wild-card placeholders (* is any string, ?
     * any character)
     *
     * 2. providing a string without placeholders to match anywhere
     *
     * @param value user input for like {@link Filter}
     * @return The SQL like expression
     */
    private static String sqlLikeExpressionOf( String value )
    {
        return value != null && (value.contains( "*" ) || value.contains( "?" ))
            ? value.replace( "*", "%" ).replace( "?", "_" )
            : "%" + value + "%";
    }
}
