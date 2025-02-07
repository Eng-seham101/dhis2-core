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
package org.hisp.dhis.dxf2.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.xml.xpath.XPathExpressionException;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.collections4.*;
import org.hisp.dhis.TransactionalIntegrationTest;
import org.hisp.dhis.category.CategoryCombo;
import org.hisp.dhis.common.*;
import org.hisp.dhis.dataset.DataSet;
import org.hisp.dhis.dataset.DataSetService;
import org.hisp.dhis.dataset.Section;
import org.hisp.dhis.dxf2.metadata.feedback.ImportReport;
import org.hisp.dhis.dxf2.metadata.objectbundle.ObjectBundleMode;
import org.hisp.dhis.feedback.ErrorCode;
import org.hisp.dhis.feedback.ErrorReport;
import org.hisp.dhis.feedback.Status;
import org.hisp.dhis.importexport.ImportStrategy;
import org.hisp.dhis.node.NodeService;
import org.hisp.dhis.node.types.RootNode;
import org.hisp.dhis.program.Program;
import org.hisp.dhis.program.ProgramStage;
import org.hisp.dhis.program.ProgramStageSection;
import org.hisp.dhis.program.ProgramStageService;
import org.hisp.dhis.query.Query;
import org.hisp.dhis.render.DeviceRenderTypeMap;
import org.hisp.dhis.render.RenderDevice;
import org.hisp.dhis.render.RenderFormat;
import org.hisp.dhis.render.RenderService;
import org.hisp.dhis.render.type.SectionRenderingObject;
import org.hisp.dhis.render.type.SectionRenderingType;
import org.hisp.dhis.schema.SchemaService;
import org.hisp.dhis.user.User;
import org.hisp.dhis.user.UserGroup;
import org.hisp.dhis.user.UserService;
import org.hisp.dhis.visualization.Visualization;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

/**
 * @author Morten Olav Hansen <mortenoh@gmail.com>
 */
@Slf4j
public class MetadataImportServiceTest extends TransactionalIntegrationTest
{
    @Autowired
    private MetadataImportService importService;

    @Autowired
    private MetadataExportService exportService;

    @Autowired
    private RenderService _renderService;

    @Autowired
    private UserService _userService;

    @Autowired
    private IdentifiableObjectManager manager;

    @Autowired
    private SchemaService schemaService;

    @Autowired
    private DataSetService dataSetService;

    @Autowired
    private ProgramStageService programStageService;

    @Autowired
    private NodeService nodeService;

    @Override
    protected void setUpTest()
        throws Exception
    {
        renderService = _renderService;
        userService = _userService;
    }

    @Test
    public void testCorrectStatusOnImportNoErrors()
        throws IOException
    {
        Map<Class<? extends IdentifiableObject>, List<IdentifiableObject>> metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/dataset_with_sections.json" ).getInputStream(), RenderFormat.JSON );

        MetadataImportParams params = createParams( ImportStrategy.CREATE, metadata );

        ImportReport report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );
    }

    @Test
    public void testCorrectStatusOnImportErrors()
        throws IOException
    {
        createUserAndInjectSecurityContext( true );

        Map<Class<? extends IdentifiableObject>, List<IdentifiableObject>> metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/dataset_with_sections.json" ).getInputStream(), RenderFormat.JSON );

        MetadataImportParams params = createParams( ImportStrategy.CREATE, metadata );
        params.setAtomicMode( AtomicMode.NONE );

        ImportReport report = importService.importMetadata( params );
        assertEquals( Status.WARNING, report.getStatus() );
    }

    @Test
    public void testCorrectStatusOnImportErrorsATOMIC()
        throws IOException
    {
        createUserAndInjectSecurityContext( true );

        Map<Class<? extends IdentifiableObject>, List<IdentifiableObject>> metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/dataset_with_sections.json" ).getInputStream(), RenderFormat.JSON );

        MetadataImportParams params = createParams( ImportStrategy.CREATE, metadata );

        ImportReport report = importService.importMetadata( params );
        assertEquals( Status.ERROR, report.getStatus() );
    }

    @Test
    public void testImportUpdatePublicAccess()
        throws IOException
    {
        Map<Class<? extends IdentifiableObject>, List<IdentifiableObject>> metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/dataset_with_accesses.json" ).getInputStream(), RenderFormat.JSON );

        MetadataImportParams params = new MetadataImportParams();
        params.setImportMode( ObjectBundleMode.COMMIT );
        params.setImportStrategy( ImportStrategy.CREATE );
        params.setObjects( metadata );

        ImportReport report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        DataSet dataSet = manager.get( DataSet.class, "em8Bg4LCr5k" );

        assertEquals( "rw------", dataSet.getPublicAccess() );

        metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/dataset_with_publicAccess_update.json" ).getInputStream(), RenderFormat.JSON );

        params = new MetadataImportParams();
        params.setImportMode( ObjectBundleMode.COMMIT );
        params.setImportStrategy( ImportStrategy.UPDATE );
        params.setObjects( metadata );

        report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        DataSet updatedDataSet = manager.get( DataSet.class, "em8Bg4LCr5k" );

        assertEquals( "r-------", updatedDataSet.getPublicAccess() );

    }

    @Test
    public void testImportWithAccessObjects()
        throws IOException
    {
        Map<Class<? extends IdentifiableObject>, List<IdentifiableObject>> metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/dataset_with_accesses.json" ).getInputStream(), RenderFormat.JSON );

        MetadataImportParams params = createParams( ImportStrategy.CREATE, metadata );

        ImportReport report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/dataset_with_accesses_update.json" ).getInputStream(), RenderFormat.JSON );

        params = createParams( ImportStrategy.UPDATE, metadata );

        report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );
    }

    @Test
    public void testImportWithSkipSharingIsTrue()
        throws IOException
    {
        User user = createUser( "A", "ALL" );
        manager.save( user );

        Map<Class<? extends IdentifiableObject>, List<IdentifiableObject>> metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/dataset_with_accesses_skipSharing.json" ).getInputStream(),
            RenderFormat.JSON );

        MetadataImportParams params = createParams( ImportStrategy.CREATE, metadata );
        params.setSkipSharing( false );
        params.setUser( user );

        ImportReport report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        DataSet dataSet = manager.get( DataSet.class, "em8Bg4LCr5k" );
        assertNotNull( dataSet.getSharing().getUserGroups() );
        assertEquals( 1, dataSet.getSharing().getUserGroups().size() );
        assertEquals( "fvz8d3u6jFd", dataSet.getSharing().getUserGroups().values().iterator().next().getId() );

        metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/dataset_with_accesses_update_skipSharing.json" ).getInputStream(),
            RenderFormat.JSON );

        params = createParams( ImportStrategy.UPDATE, metadata );
        params.setSkipSharing( true );
        params.setUser( user );

        report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        DataSet dataSetUpdated = manager.get( DataSet.class, "em8Bg4LCr5k" );
        assertNotNull( dataSetUpdated.getSharing().getUserGroups() );
        assertEquals( 1, dataSetUpdated.getSharing().getUserGroups().size() );
        assertNotNull( dataSetUpdated.getSharing().getUserGroups().get( "fvz8d3u6jFd" ) );
    }

    @Test
    public void testImportWithSkipSharingIsFalse()
        throws IOException
    {
        User user = createUser( "A", "ALL" );
        manager.save( user );

        Map<Class<? extends IdentifiableObject>, List<IdentifiableObject>> metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/dataset_with_accesses_skipSharing.json" ).getInputStream(),
            RenderFormat.JSON );

        MetadataImportParams params = createParams( ImportStrategy.CREATE, metadata );
        params.setSkipSharing( false );
        params.setUser( user );

        ImportReport report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        DataSet dataSet = manager.get( DataSet.class, "em8Bg4LCr5k" );
        assertNotNull( dataSet.getSharing().getUserGroups() );
        assertEquals( 1, dataSet.getSharing().getUserGroups().size() );
        assertEquals( "fvz8d3u6jFd", dataSet.getSharing().getUserGroups().values().iterator().next().getId() );

        metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/dataset_with_accesses_update_skipSharing.json" ).getInputStream(),
            RenderFormat.JSON );

        params = createParams( ImportStrategy.UPDATE, metadata );
        params.setSkipSharing( false );
        params.setUser( user );

        report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        DataSet dataSetUpdated = manager.get( DataSet.class, "em8Bg4LCr5k" );
        assertTrue( MapUtils.isEmpty( dataSetUpdated.getSharing().getUserGroups() ) );
    }

    @Test
    public void testImportNewObjectWithSkipTranslationIsTrue()
        throws IOException
    {
        User user = createUser( "A", "ALL" );
        manager.save( user );

        Map<Class<? extends IdentifiableObject>, List<IdentifiableObject>> metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/dataset_with_accesses_skipSharing.json" ).getInputStream(),
            RenderFormat.JSON );

        MetadataImportParams params = createParams( ImportStrategy.CREATE, metadata );
        params.setSkipTranslation( true );
        params.setUser( user );

        ImportReport report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );
        DataSet dataSet = manager.get( DataSet.class, "em8Bg4LCr5k" );
        assertNotNull( dataSet.getSharing().getUserGroups() );

        // Payload has translations but skipTranslation = true
        assertEquals( 0, dataSet.getTranslations().size() );

    }

    @Test
    public void testImportNewObjectWithSkipTranslationIsFalse()
        throws IOException
    {
        User user = createUser( "A", "ALL" );
        manager.save( user );
        Map<Class<? extends IdentifiableObject>, List<IdentifiableObject>> metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/dataset_with_accesses_skipSharing.json" ).getInputStream(),
            RenderFormat.JSON );

        MetadataImportParams params = createParams( ImportStrategy.CREATE, metadata );
        params.setSkipTranslation( false );
        params.setUser( user );

        ImportReport report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        DataSet dataSet = manager.get( DataSet.class, "em8Bg4LCr5k" );
        assertNotNull( dataSet.getSharing().getUserGroups() );

        // Payload has translations and skipTranslation = false
        assertEquals( 2, dataSet.getTranslations().size() );
    }

    /**
     * 1. Create object with 2 translations 2. Update object with empty
     * translations and skipTranslation = false Expected: updated object has
     * empty translations
     */
    @Test
    public void testUpdateWithSkipTranslationIsFalse()
        throws IOException
    {
        User user = createUser( "A", "ALL" );
        manager.save( user );

        Map<Class<? extends IdentifiableObject>, List<IdentifiableObject>> metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/dataset_with_accesses_skipSharing.json" ).getInputStream(),
            RenderFormat.JSON );

        MetadataImportParams params = createParams( ImportStrategy.CREATE, metadata );
        params.setSkipTranslation( false );
        params.setUser( user );

        ImportReport report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        DataSet dataSet = manager.get( DataSet.class, "em8Bg4LCr5k" );
        assertNotNull( dataSet.getSharing().getUserGroups() );
        assertEquals( 2, dataSet.getTranslations().size() );

        metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/dataset_with_accesses_update_skipSharing.json" ).getInputStream(),
            RenderFormat.JSON );

        params = createParams( ImportStrategy.UPDATE, metadata );
        params.setSkipTranslation( false );
        params.setUser( user );

        report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        dataSet = manager.get( DataSet.class, "em8Bg4LCr5k" );
        assertNotNull( dataSet.getSharing().getUserGroups() );

        assertEquals( 0, dataSet.getTranslations().size() );
    }

    /**
     * 1. Create object with 2 translations 2. Update object with empty
     * translations and skipTranslation = true Expected: updated object still
     * has 2 translations
     */
    @Test
    public void testUpdateWithSkipTranslationIsTrue()
        throws IOException
    {
        User user = createUser( "A", "ALL" );
        manager.save( user );

        Map<Class<? extends IdentifiableObject>, List<IdentifiableObject>> metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/dataset_with_accesses_skipSharing.json" ).getInputStream(),
            RenderFormat.JSON );

        MetadataImportParams params = createParams( ImportStrategy.CREATE, metadata );
        params.setSkipTranslation( false );
        params.setUser( user );

        ImportReport report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        DataSet dataSet = manager.get( DataSet.class, "em8Bg4LCr5k" );
        assertNotNull( dataSet.getSharing().getUserGroups() );
        assertEquals( 2, dataSet.getTranslations().size() );

        metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/dataset_with_accesses_update_skipSharing.json" ).getInputStream(),
            RenderFormat.JSON );

        params = createParams( ImportStrategy.UPDATE, metadata );
        params.setSkipTranslation( true );
        params.setUser( user );

        report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        dataSet = manager.get( DataSet.class, "em8Bg4LCr5k" );
        assertNotNull( dataSet.getSharing().getUserGroups() );

        assertEquals( 2, dataSet.getTranslations().size() );
    }

    @Test( expected = IllegalArgumentException.class )
    public void testImportNonExistingEntityObject()
        throws IOException
    {
        User user = createUser( 'A' );
        manager.save( user );

        UserGroup userGroup = createUserGroup( 'A', Sets.newHashSet( user ) );
        manager.save( userGroup );

        userGroup = manager.get( UserGroup.class, "ugabcdefghA" );
        assertNotNull( userGroup );

        Map<Class<? extends IdentifiableObject>, List<IdentifiableObject>> metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/favorites/metadata_chart_with_accesses.json" ).getInputStream(),
            RenderFormat.JSON );

        MetadataImportParams params = createParams( ImportStrategy.CREATE, metadata );

        importService.importMetadata( params );

        // Should not get to this point.
        fail( "The exception org.hibernate.MappingException was expected." );
    }

    @Test
    public void testImportMultiPropertyUniqueness()
        throws IOException
    {
        Map<Class<? extends IdentifiableObject>, List<IdentifiableObject>> metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/favorites/metadata_multi_property_uniqueness.json" ).getInputStream(),
            RenderFormat.JSON );

        MetadataImportParams params = createParams( ImportStrategy.CREATE, metadata );

        ImportReport importReport = importService.importMetadata( params );

        assertTrue( importReport.getTypeReports().stream()
            .flatMap( typeReport -> typeReport.getErrorReports().stream() )
            .anyMatch( errorReport -> errorReport.getErrorCode().equals( ErrorCode.E5005 ) ) );
    }

    @Test
    public void testImportEmbeddedObjectWithSkipSharingIsTrue()
        throws IOException
    {
        User user = createUser( 'A' );
        manager.save( user );

        UserGroup userGroup = createUserGroup( 'A', Sets.newHashSet( user ) );
        manager.save( userGroup );

        userGroup = manager.get( UserGroup.class, "ugabcdefghA" );
        assertNotNull( userGroup );

        Map<Class<? extends IdentifiableObject>, List<IdentifiableObject>> metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/favorites/metadata_visualization_with_accesses.json" ).getInputStream(),
            RenderFormat.JSON );

        MetadataImportParams params = createParams( ImportStrategy.CREATE, metadata );

        ImportReport report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        Visualization visualization = manager.get( Visualization.class, "gyYXi0rXAIc" );
        assertNotNull( visualization );
        assertEquals( 1, visualization.getUserGroupAccesses().size() );
        assertEquals( 1, visualization.getUserAccesses().size() );
        assertEquals( user.getUid(), visualization.getUserAccesses().iterator().next().getUserUid() );
        assertEquals( userGroup.getUid(), visualization.getUserGroupAccesses().iterator().next().getUserGroupUid() );

        Visualization dataElementOperandVisualization = manager.get(
            Visualization.class, "qD72aBqsHvt" );
        assertNotNull( dataElementOperandVisualization );
        assertEquals( 2,
            dataElementOperandVisualization.getDataDimensionItems().size() );
        dataElementOperandVisualization.getDataDimensionItems()
            .stream()
            .forEach( item -> assertNotNull( item.getDataElementOperand() ) );

        metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/favorites/metadata_visualization_with_accesses_update.json" ).getInputStream(),
            RenderFormat.JSON );

        params = createParams( ImportStrategy.UPDATE, metadata );
        params.setSkipSharing( true );

        dbmsManager.clearSession();

        report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        visualization = manager.get( Visualization.class, "gyYXi0rXAIc" );
        assertNotNull( visualization );
        assertEquals( 1, visualization.getUserGroupAccesses().size() );
        assertEquals( 1, visualization.getUserAccesses().size() );
        assertEquals( user.getUid(), visualization.getUserAccesses().iterator().next().getUser().getUid() );
        assertEquals( userGroup.getUid(),
            visualization.getUserGroupAccesses().iterator().next().getUserGroup().getUid() );
    }

    @Test
    public void testImportEmbeddedObjectWithSkipSharingIsFalse()
        throws IOException
    {
        User user = createUser( 'A' );
        manager.save( user );

        UserGroup userGroup = createUserGroup( 'A', Sets.newHashSet( user ) );
        manager.save( userGroup );

        userGroup = manager.get( UserGroup.class, "ugabcdefghA" );
        assertNotNull( userGroup );

        Map<Class<? extends IdentifiableObject>, List<IdentifiableObject>> metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/favorites/metadata_visualization_with_accesses.json" ).getInputStream(),
            RenderFormat.JSON );

        MetadataImportParams params = createParams( ImportStrategy.CREATE, metadata );
        params.setSkipSharing( false );

        ImportReport report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        dbmsManager.clearSession();

        Visualization visualization = manager.get( Visualization.class, "gyYXi0rXAIc" );
        assertNotNull( visualization );
        assertEquals( 1, visualization.getUserGroupAccesses().size() );
        assertEquals( 1, visualization.getUserAccesses().size() );
        assertEquals( user.getUid(), visualization.getUserAccesses().iterator().next().getUser().getUid() );
        assertEquals( userGroup.getUid(),
            visualization.getUserGroupAccesses().iterator().next().getUserGroup().getUid() );

        metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/favorites/metadata_visualization_with_accesses_update.json" ).getInputStream(),
            RenderFormat.JSON );

        params = createParams( ImportStrategy.UPDATE, metadata );
        params.setSkipSharing( false );

        dbmsManager.clearSession();

        report = importService.importMetadata( params );

        assertEquals( Status.OK, report.getStatus() );

        visualization = manager.get( Visualization.class, "gyYXi0rXAIc" );
        assertNotNull( visualization );
        assertEquals( 0, visualization.getUserGroupAccesses().size() );
        assertEquals( 0, visualization.getUserAccesses().size() );
    }

    /**
     * 1. Create an object with UserGroupAccessA 2. Update object with only
     * UserGroupAccessB in payload and mergeMode=MERGE Expected: updated object
     * will have two UserGroupAccesses
     *
     * @throws IOException
     */
    @Test
    public void testImportSharingWithMergeModeMerge()
        throws IOException
    {
        User user = createUser( "A", "ALL" );
        manager.save( user );

        Map<Class<? extends IdentifiableObject>, List<IdentifiableObject>> metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/dataset_with_accesses_skipSharing.json" ).getInputStream(),
            RenderFormat.JSON );

        MetadataImportParams params = createParams( ImportStrategy.CREATE, metadata );
        params.setUser( user );

        ImportReport report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        DataSet dataSet = manager.get( DataSet.class, "em8Bg4LCr5k" );
        assertNotNull( dataSet.getSharing().getUserGroups() );
        assertEquals( 1, dataSet.getSharing().getUserGroups().size() );

        metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/dataset_with_accesses_merge_mode.json" ).getInputStream(),
            RenderFormat.JSON );

        params = createParams( ImportStrategy.CREATE_AND_UPDATE, metadata );
        params.setMergeMode( MergeMode.MERGE );
        params.setUser( user );

        report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        dataSet = manager.get( DataSet.class, "em8Bg4LCr5k" );
        assertNotNull( dataSet.getSharing().getUserGroups() );

        assertEquals( 2, dataSet.getSharing().getUserGroups().size() );
    }

    /**
     * 1. Create an object with UserGroupAccessA 2. Update object with only
     * UserGroupAccessB in payload and mergeMode=REPLACE Expected: updated
     * object will have only UserGroupAccessB
     *
     * @throws IOException
     */
    @Test
    public void testImportSharingWithMergeModeReplace()
        throws IOException
    {
        User user = createUser( "A", "ALL" );
        manager.save( user );

        Map<Class<? extends IdentifiableObject>, List<IdentifiableObject>> metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/dataset_with_accesses_skipSharing.json" ).getInputStream(),
            RenderFormat.JSON );

        MetadataImportParams params = createParams( ImportStrategy.CREATE, metadata );
        params.setUser( user );

        ImportReport report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        DataSet dataSet = manager.get( DataSet.class, "em8Bg4LCr5k" );
        assertNotNull( dataSet.getSharing().getUserGroups() );
        assertEquals( 1, dataSet.getSharing().getUserGroups().size() );

        metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/dataset_with_accesses_merge_mode.json" ).getInputStream(),
            RenderFormat.JSON );

        params = createParams( ImportStrategy.CREATE_AND_UPDATE, metadata );
        params.setMergeMode( MergeMode.REPLACE );
        params.setUser( user );

        report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        dataSet = manager.get( DataSet.class, "em8Bg4LCr5k" );
        assertNotNull( dataSet.getSharing().getUserGroups() );

        assertEquals( 1, dataSet.getSharing().getUserGroups().size() );
        assertNotNull( dataSet.getSharing().getUserGroups().get( "FnJeHbPOtVF" ) );
    }

    @Test
    public void testImportProgramWithProgramStageSections()
        throws IOException
    {
        Map<Class<? extends IdentifiableObject>, List<IdentifiableObject>> metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/program_noreg_sections.json" ).getInputStream(), RenderFormat.JSON );

        MetadataImportParams params = createParams( ImportStrategy.CREATE, metadata );

        ImportReport report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        Program program = manager.get( Program.class, "s5uvS0Q7jnX" );

        assertNotNull( program );
        assertEquals( 1, program.getProgramStages().size() );

        ProgramStage programStage = program.getProgramStages().iterator().next();
        assertNotNull( programStage.getProgram() );

        Set<ProgramStageSection> programStageSections = programStage.getProgramStageSections();
        assertNotNull( programStageSections );
        assertEquals( 2, programStageSections.size() );

        ProgramStageSection programStageSection = programStageSections.iterator().next();
        assertNotNull( programStageSection.getProgramStage() );
    }

    @Test
    public void testMetadataSyncWithDeletedDataSetSection()
        throws IOException
    {
        Map<Class<? extends IdentifiableObject>, List<IdentifiableObject>> metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/dataset_with_sections.json" ).getInputStream(), RenderFormat.JSON );

        MetadataImportParams params = createParams( ImportStrategy.CREATE_AND_UPDATE, metadata );
        ImportReport report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        dbmsManager.clearSession();

        DataSet dataset = dataSetService.getDataSet( "em8Bg4LCr5k" );
        assertNotNull( dataset.getSections() );
        assertNotNull( manager.get( Section.class, "JwcV2ZifEQf" ) );

        metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/dataset_with_removed_section.json" ).getInputStream(), RenderFormat.JSON );

        params = createParams( ImportStrategy.UPDATE, metadata );
        params.setMetadataSyncImport( true );

        dbmsManager.clearSession();

        report = importService.importMetadata( params );
        final List<ErrorReport> errorReports = report.getErrorReports();
        for ( ErrorReport errorReport : errorReports )
        {
            log.error( "Error report:" + errorReport );
        }
        assertEquals( Status.OK, report.getStatus() );

        dataset = manager.get( DataSet.class, "em8Bg4LCr5k" );
        assertEquals( 1, dataset.getSections().size() );
        assertNull( manager.get( Section.class, "JwcV2ZifEQf" ) );

        metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/dataset_with_all_section_removed.json" ).getInputStream(), RenderFormat.JSON );
        params = createParams( ImportStrategy.CREATE_AND_UPDATE, metadata );
        params.setMetadataSyncImport( true );

        dbmsManager.clearSession();

        report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        dataset = manager.get( DataSet.class, "em8Bg4LCr5k" );
        assertTrue( dataset.getSections().isEmpty() );
    }

    @Test
    public void testMetadataImportWithDeletedProgramStageSection()
        throws IOException
    {
        Map<Class<? extends IdentifiableObject>, List<IdentifiableObject>> metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/programstage_with_sections.json" ).getInputStream(), RenderFormat.JSON );

        MetadataImportParams params = createParams( ImportStrategy.CREATE_AND_UPDATE, metadata );

        ImportReport report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        dbmsManager.clearSession();

        ProgramStage programStage = programStageService.getProgramStage( "NpsdDv6kKSO" );

        assertNotNull( programStage.getProgramStageSections() );

        assertNotNull( manager.get( ProgramStageSection.class, "JwcV2ZifEQf" ) );

        CategoryCombo categoryCombo = manager.get( CategoryCombo.class, "faV8QvLgIwB" );

        metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/programstage_with_removed_section.json" ).getInputStream(),
            RenderFormat.JSON );

        params = createParams( ImportStrategy.UPDATE, metadata );
        params.setMetadataSyncImport( true );

        report = importService.importMetadata( params );
        final List<ErrorReport> errorReports = report.getErrorReports();
        for ( ErrorReport errorReport : errorReports )
        {
            log.error( "Error report:" + errorReport );
        }
        assertEquals( Status.OK, report.getStatus() );

        programStage = manager.get( ProgramStage.class, "NpsdDv6kKSO" );

        assertEquals( 1, programStage.getProgramStageSections().size() );

        assertNull( manager.get( ProgramStageSection.class, "JwcV2ZifEQf" ) );

        metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/programstage_with_all_section_removed.json" ).getInputStream(),
            RenderFormat.JSON );
        params.setImportMode( ObjectBundleMode.COMMIT );
        params.setImportStrategy( ImportStrategy.UPDATE );
        params.setObjects( metadata );
        params.setMetadataSyncImport( true );

        report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        programStage = manager.get( ProgramStage.class, "NpsdDv6kKSO" );

        assertEquals( true, programStage.getProgramStageSections().isEmpty() );

    }

    @Test
    public void testMetadataImportWithDeletedDataElements()
        throws IOException
    {
        Map<Class<? extends IdentifiableObject>, List<IdentifiableObject>> metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/dataset_with_sections_and_data_elements.json" ).getInputStream(),
            RenderFormat.JSON );

        MetadataImportParams params = createParams( ImportStrategy.CREATE_AND_UPDATE, metadata );

        ImportReport report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        DataSet dataset = dataSetService.getDataSet( "em8Bg4LCr5k" );

        assertNotNull( dataset.getSections() );
        assertNotNull( dataset.getDataElements() );
        assertTrue( dataset.getDataElements().stream().map( de -> de.getUid() ).collect( Collectors.toList() )
            .contains( "R45hiT7RLui" ) );

        assertNotNull( manager.get( Section.class, "JwcV2ZifEQf" ) );

        assertTrue( dataset.getSections().stream().filter( s -> s.getUid().equals( "JwcV2ZifEQf" ) ).findFirst().get()
            .getDataElements().stream().map( de -> de.getUid() ).collect( Collectors.toList() )
            .contains( "R45hiT7RLui" ) );

        metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/dataset_with_data_element_removed.json" ).getInputStream(),
            RenderFormat.JSON );

        params = createParams( ImportStrategy.CREATE_AND_UPDATE, metadata );
        params.setMetadataSyncImport( false );

        report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        dataset = manager.get( DataSet.class, "em8Bg4LCr5k" );

        assertFalse( dataset.getDataElements().stream().map( de -> de.getUid() ).collect( Collectors.toList() )
            .contains( "R45hiT7RLui" ) );

        assertNotNull( manager.get( Section.class, "JwcV2ZifEQf" ) );

        assertFalse( dataset.getSections().stream().filter( s -> s.getUid().equals( "JwcV2ZifEQf" ) ).findFirst().get()
            .getDataElements().stream().map( de -> de.getUid() ).collect( Collectors.toList() )
            .contains( "R45hiT7RLui" ) );
    }

    @Test
    public void testUpdateUserGroupWithoutCreatedUserProperty()
        throws IOException
    {
        User userA = createUser( 'A', Lists.newArrayList( "ALL" ) );
        userService.addUser( userA );

        Map<Class<? extends IdentifiableObject>, List<IdentifiableObject>> metadata = renderService
            .fromMetadata( new ClassPathResource( "dxf2/usergroups.json" ).getInputStream(), RenderFormat.JSON );

        MetadataImportParams params = createParams( ImportStrategy.CREATE, metadata );
        params.setUser( userA );

        ImportReport report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        UserGroup userGroup = manager.get( UserGroup.class, "OPVIvvXzNTw" );
        assertEquals( userA.getUid(), userGroup.getSharing().getOwner() );

        User userB = createUser( "B", "ALL" );
        userService.addUser( userB );

        metadata = renderService.fromMetadata( new ClassPathResource( "dxf2/usergroups_update.json" ).getInputStream(),
            RenderFormat.JSON );

        params = createParams( ImportStrategy.UPDATE, metadata );
        params.setUser( userB );

        report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        userGroup = manager.get( UserGroup.class, "OPVIvvXzNTw" );
        assertEquals( "TA user group updated", userGroup.getName() );
        assertEquals( userA.getUid(), userGroup.getSharing().getOwner() );
    }

    @Test
    public void testSerializeDeviceRenderTypeMap()
        throws IOException,
        XPathExpressionException
    {
        Metadata metadata = renderService.fromXml(
            new ClassPathResource( "dxf2/programstagesection_with_deps.xml" ).getInputStream(), Metadata.class );

        MetadataImportParams params = new MetadataImportParams();
        params.setImportMode( ObjectBundleMode.COMMIT );
        params.setImportStrategy( ImportStrategy.CREATE_AND_UPDATE );
        params.addMetadata( schemaService.getMetadataSchemas(), metadata );

        ImportReport report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        manager.flush();

        ProgramStageSection programStageSection = manager.get( ProgramStageSection.class, "e99B1JXVMMQ" );
        assertNotNull( programStageSection );
        assertEquals( 2, programStageSection.getRenderType().size() );
        DeviceRenderTypeMap<SectionRenderingObject> renderingType = programStageSection.getRenderType();

        SectionRenderingObject renderDevice1 = renderingType.get( RenderDevice.MOBILE );
        SectionRenderingObject renderDevice2 = renderingType.get( RenderDevice.DESKTOP );

        assertEquals( SectionRenderingType.SEQUENTIAL, renderDevice1.getType() );
        assertEquals( SectionRenderingType.LISTING, renderDevice2.getType() );

        MetadataExportParams exportParams = new MetadataExportParams();
        exportParams.addQuery( Query.from( schemaService.getSchema( ProgramStageSection.class ) ) );

        RootNode rootNode = exportService.getMetadataAsNode( exportParams );

        OutputStream outputStream = new ByteArrayOutputStream();

        nodeService.serialize( rootNode, "application/xml", outputStream );
        assertEquals( "1", xpathTest( "count(//d:programStageSection)", outputStream.toString() ) );
        assertEquals( "SEQUENTIAL", xpathTest( "//d:MOBILE/@type", outputStream.toString() ) );
        assertEquals( "LISTING", xpathTest( "//d:DESKTOP/@type", outputStream.toString() ) );
    }

    @Test
    public void testUpdateImmutableCreatedByField()
        throws IOException
    {
        User userA = createUser( 'A', Lists.newArrayList( "ALL" ) );
        userService.addUser( userA );

        Map<Class<? extends IdentifiableObject>, List<IdentifiableObject>> metadata = renderService
            .fromMetadata( new ClassPathResource( "dxf2/usergroups.json" ).getInputStream(), RenderFormat.JSON );

        MetadataImportParams params = createParams( ImportStrategy.CREATE, metadata );
        params.setUser( userA );

        ImportReport report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        UserGroup userGroup = manager.get( UserGroup.class, "OPVIvvXzNTw" );
        assertEquals( userA.getUid(), userGroup.getCreatedBy().getUid() );

        User userB = createUser( "B", "ALL" );
        userB.setUid( "userabcdefB" );
        userService.addUser( userB );

        metadata = renderService.fromMetadata( new ClassPathResource( "dxf2/usergroups_update.json" ).getInputStream(),
            RenderFormat.JSON );

        params = createParams( ImportStrategy.UPDATE, metadata );
        params.setUser( userA );

        report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        userGroup = manager.get( UserGroup.class, "OPVIvvXzNTw" );
        assertEquals( "TA user group updated", userGroup.getName() );
        assertEquals( userA.getUid(), userGroup.getCreatedBy().getUid() );
    }

    @Test
    public void testImportUser()
        throws IOException
    {
        User userF = createUser( 'F', Lists.newArrayList( "ALL" ) );
        userService.addUser( userF );

        Map<Class<? extends IdentifiableObject>, List<IdentifiableObject>> metadata = renderService.fromMetadata(
            new ClassPathResource( "dxf2/create_user_without_createdBy.json" ).getInputStream(), RenderFormat.JSON );

        MetadataImportParams params = createParams( ImportStrategy.CREATE_AND_UPDATE, metadata );
        params.setUser( userF );
        ImportReport report = importService.importMetadata( params );
        assertEquals( Status.OK, report.getStatus() );

        User user = manager.get( User.class, "MwhEJUnTHkn" );
        assertNotNull( user.getUserCredentials().getCreatedBy() );
    }

    private MetadataImportParams createParams( ImportStrategy importStrategy,
        Map<Class<? extends IdentifiableObject>, List<IdentifiableObject>> metadata )
    {
        MetadataImportParams params = new MetadataImportParams();
        params.setImportMode( ObjectBundleMode.COMMIT );
        params.setImportStrategy( importStrategy );
        params.setObjects( metadata );

        return params;
    }
}
