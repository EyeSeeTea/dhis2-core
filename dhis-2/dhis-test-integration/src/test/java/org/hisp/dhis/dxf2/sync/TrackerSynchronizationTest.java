/*
 * Copyright (c) 2004-2022, University of Oslo
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
package org.hisp.dhis.dxf2.sync;

import static org.hisp.dhis.security.acl.AccessStringHelper.FULL;
import static org.hisp.dhis.utils.Assertions.assertContainsOnly;
import static org.hisp.dhis.utils.Assertions.assertIsEmpty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.*;
import java.util.stream.Collectors;
import org.hisp.dhis.common.IdentifiableObjectManager;
import org.hisp.dhis.common.ValueType;
import org.hisp.dhis.dataelement.DataElement;
import org.hisp.dhis.dataelement.DataElementDomain;
import org.hisp.dhis.dxf2.events.EnrollmentEventsParams;
import org.hisp.dhis.dxf2.events.EnrollmentParams;
import org.hisp.dhis.dxf2.events.enrollment.Enrollment;
import org.hisp.dhis.dxf2.events.enrollment.EnrollmentService;
import org.hisp.dhis.dxf2.events.TrackedEntityInstanceEnrollmentParams;
import org.hisp.dhis.dxf2.events.TrackedEntityInstanceParams;
import org.hisp.dhis.dxf2.events.trackedentity.TrackedEntityInstanceService;
import org.hisp.dhis.dxf2.events.trackedentity.TrackedEntityInstances;
import org.hisp.dhis.eventdatavalue.EventDataValue;
import org.hisp.dhis.organisationunit.OrganisationUnit;
import org.hisp.dhis.program.*;
import org.hisp.dhis.program.ProgramStageInstanceService;
import org.hisp.dhis.test.integration.SingleSetupIntegrationTestBase;
import org.hisp.dhis.trackedentity.TrackedEntityAttribute;
import org.hisp.dhis.trackedentity.TrackedEntityInstance;
import org.hisp.dhis.trackedentity.TrackedEntityInstanceQueryParams;
import org.hisp.dhis.trackedentity.TrackedEntityType;
import org.hisp.dhis.trackedentity.TrackedEntityTypeAttribute;
import org.hisp.dhis.trackedentityattributevalue.TrackedEntityAttributeValue;
import org.hisp.dhis.trackedentityattributevalue.TrackedEntityAttributeValueService;
import org.hisp.dhis.attribute.AttributeValue;
import org.hisp.dhis.user.User;
import org.hisp.dhis.user.UserService;
import org.hisp.dhis.user.sharing.Sharing;
import org.hisp.dhis.util.DateUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * @author David Katuscak (katuscak.d@gmail.com)
 */
class TrackerSynchronizationTest extends SingleSetupIntegrationTestBase {
  // We need to pick a future date as lastUpdated is automatically set to now and cannot be changed
  private static final Date TOMORROW = DateUtils.getDateForTomorrow(0);

  private static final String TEI_NOT_IN_SYNC_UID = "ABCDEFGHI01";

  private static final String SYNCHRONIZED_TEI_UID = "ABCDEFGHI02";

  private static final String SYNCHRONIZED_ENR_UID = "ABCDEFGHI03";

  private static final String SYNCHRONIZED_EVENT_UID = "ABCDEFGHI04";

  @Autowired private UserService _userService;

  @Autowired private TrackedEntityAttributeValueService trackedEntityAttributeValueService;

  @Autowired private ProgramStageInstanceService programStageInstanceService;

  @Autowired private ProgramStageDataElementService programStageDataElementService;

  @Autowired private ProgramInstanceService programInstanceService;

  @Autowired private EnrollmentService enrollmentService;

  @Autowired private IdentifiableObjectManager manager;

  @Autowired private TrackedEntityInstanceService subject;

  private TrackedEntityInstanceQueryParams queryParams;

  private TrackedEntityInstanceParams params;

  private void prepareDataForTest(User user) {
    TrackedEntityAttribute teaA = createTrackedEntityAttribute('a');
    TrackedEntityAttribute teaB = createTrackedEntityAttribute('b');
    teaB.setSkipSynchronization(true);
    manager.save(teaA);
    manager.save(teaB);
    TrackedEntityType tet = createTrackedEntityType('a');
    TrackedEntityTypeAttribute tetaA = new TrackedEntityTypeAttribute(tet, teaA, true, false);
    TrackedEntityTypeAttribute tetaB = new TrackedEntityTypeAttribute(tet, teaB, true, false);
    tet.getTrackedEntityTypeAttributes().add(tetaA);
    tet.getTrackedEntityTypeAttributes().add(tetaB);
    manager.save(tet);
    Program program = createProgram('a');
    program.setProgramType(ProgramType.WITH_REGISTRATION);
    program.setTrackedEntityType(tet);
    program.setSharing(Sharing.builder().publicAccess(FULL).build());
    manager.save(program);
    OrganisationUnit ou = createOrganisationUnit('a');
    manager.save(ou);
    TrackedEntityInstance teiToSync = createTrackedEntityInstance('a', ou, teaA);
    teiToSync.setTrackedEntityType(tet);
    teiToSync.setUid(TEI_NOT_IN_SYNC_UID);
    TrackedEntityAttributeValue teavB = createTrackedEntityAttributeValue('b', teiToSync, teaB);
    TrackedEntityAttributeValue teavA = createTrackedEntityAttributeValue('a', teiToSync, teaA);
    manager.save(teiToSync);
    trackedEntityAttributeValueService.addTrackedEntityAttributeValue(teavA);
    trackedEntityAttributeValueService.addTrackedEntityAttributeValue(teavB);
    teiToSync.getTrackedEntityAttributeValues().addAll(List.of(teavA, teavB));
    manager.update(teiToSync);
    ProgramInstance enrA = createProgramInstance(program, teiToSync, ou);
    enrA.enrollTrackedEntityInstance(teiToSync, program);
    programInstanceService.addProgramInstance(enrA);
    enrA.setUid(SYNCHRONIZED_ENR_UID);
    enrA.setUser(user);
    manager.save(enrA);
    ProgramStage programStage = createProgramStage('A', program);
    manager.save(programStage);
    DataElement deA = createDataElement('A');
    deA.setValueType(ValueType.TEXT);
    deA.setDomainType(DataElementDomain.TRACKER);
    DataElement deB = createDataElement('B');
    deB.setValueType(ValueType.TEXT);
    deB.setDomainType(DataElementDomain.TRACKER);
    manager.save(deA);
    manager.save(deB);
    ProgramStageDataElement psdeA = createProgramStageDataElement(programStage, deA, 1);
    ProgramStageDataElement psdeB = createProgramStageDataElement(programStage, deB, 2);
    psdeA.setSkipSynchronization(true);
    manager.save(psdeA);
    manager.save(psdeB);
    programStage.getProgramStageDataElements().addAll(List.of(psdeA, psdeB));
    manager.update(programStage);
    System.out.println("programStage.getProgramStageDataElements(): " + programStage.getProgramStageDataElements());

    EventDataValue dvA = new EventDataValue(deA.getUid(), "Skip Sync", UserInfoSnapshot.from(user));
    EventDataValue dvB = new EventDataValue(deB.getUid(), "Value", UserInfoSnapshot.from(user));

    Set<EventDataValue> dataValues = Set.of(dvA, dvB);
    System.out.println("dataValues: " + dataValues);

    Map<DataElement, EventDataValue> dataElementEventDataValueMap = new HashMap<>();
    dataElementEventDataValueMap.put(deA, dvA);
    dataElementEventDataValueMap.put(deB, dvB);
    System.out.println("dataElementEventDataValueMap: " + dataElementEventDataValueMap);


    ProgramStageInstance psiA = createProgramStageInstance(enrA, programStage, ou, dataValues);
    psiA.setUser(user);
    psiA.setStoredBy(user.getUid());
    psiA.setProgramInstance(enrA);
    psiA.setCreatedBy(user);
    psiA.setUid(SYNCHRONIZED_EVENT_UID);
    programStageInstanceService.addProgramStageInstance(psiA);
    programStageInstanceService.saveEventDataValuesAndSaveProgramStageInstance(psiA,dataElementEventDataValueMap);
    manager.save(psiA);
    System.out.println("psiA: " + psiA);
    System.out.println("psiA.getEventDataValues(): " + psiA.getEventDataValues());
    enrA.setProgramStageInstances(Set.of(psiA));
    programInstanceService.updateProgramInstance(enrA);
    manager.update(enrA);
    programStageInstanceService.updateProgramStageInstance(psiA);
    System.out.println("enrA: " + enrA);
    System.out.println("enrA.getProgramStageInstances(): " + enrA.getProgramStageInstances());
    System.out.println("enrA.getProgramStageInstances().getEventDataValues(): " + enrA.getProgramStageInstances().stream().flatMap(psi -> psi.getEventDataValues().stream()).collect(Collectors.toList()));
    // teiToSync.getProgramInstances().add(enrA);
    manager.update(teiToSync);
    System.out.println("teiToSync.enrollments: " + teiToSync.getProgramInstances());
    System.out.println("teiToSync.enrollments events: " + teiToSync.getProgramInstances().stream().flatMap(enr -> enr.getProgramStageInstances().stream()).collect(Collectors.toList()));
    TrackedEntityInstance alreadySynchronizedTei = createTrackedEntityInstance('b', ou);
    alreadySynchronizedTei.setTrackedEntityType(tet);
    alreadySynchronizedTei.setLastSynchronized(TOMORROW);
    alreadySynchronizedTei.setUid(SYNCHRONIZED_TEI_UID);
    manager.save(alreadySynchronizedTei);
    //manager.flush();
  }

  @Override
  public void setUpTest() {
    userService = _userService;
    User user = createUserWithAuth("userUID0001");
    manager.save(user);
    prepareSyncParams();
    prepareDataForTest(user);
  }

  private void prepareSyncParams() {
    queryParams = new TrackedEntityInstanceQueryParams();
    queryParams.setIncludeDeleted(true);
    params =
        new TrackedEntityInstanceParams(
                true, new TrackedEntityInstanceEnrollmentParams(true, new EnrollmentParams(EnrollmentEventsParams.TRUE, true, true, true, true)), true, false, true, true);
  }

  @Test
  void shouldReturnAllTeisWhenNotSyncQuery() {
    queryParams.setSynchronizationQuery(false);
    queryParams.setSkipChangedBefore(null);

    List<org.hisp.dhis.dxf2.events.trackedentity.TrackedEntityInstance> fetchedTeis =
        subject.getTrackedEntityInstances(queryParams, params, true, true);

    assertContainsOnly(
        List.of(TEI_NOT_IN_SYNC_UID, SYNCHRONIZED_TEI_UID),
        fetchedTeis.stream().map(t -> t.getTrackedEntityInstance()).collect(Collectors.toList()));
    assertEquals(1, getTeiByUid(fetchedTeis, TEI_NOT_IN_SYNC_UID).getAttributes().size());
  }

  @Test
  void shouldNotSynchronizeTeiUpdatedBeforeLastSync() {
    queryParams.setSynchronizationQuery(true);
    queryParams.setSkipChangedBefore(null);

    List<org.hisp.dhis.dxf2.events.trackedentity.TrackedEntityInstance> fetchedTeis =
        subject.getTrackedEntityInstances(queryParams, params, true, true);

    assertContainsOnly(
        List.of(TEI_NOT_IN_SYNC_UID),
        fetchedTeis.stream().map(t -> t.getTrackedEntityInstance()).collect(Collectors.toList()));
    assertEquals(1, getTeiByUid(fetchedTeis, TEI_NOT_IN_SYNC_UID).getAttributes().size());
  }

  @Test
  void shouldNotSynchronizeTeiUpdatedBeforeSkipChangedBeforeDate() {
    queryParams.setSynchronizationQuery(true);
    queryParams.setSkipChangedBefore(TOMORROW);

    List<org.hisp.dhis.dxf2.events.trackedentity.TrackedEntityInstance> fetchedTeis =
        subject.getTrackedEntityInstances(queryParams, params, true, true);

    assertIsEmpty(fetchedTeis);
  }

  @Test
  void shouldNotSynchronizeDataWithSkipSynchronizationFlag() {
    queryParams.setSynchronizationQuery(true);
    queryParams.setSkipChangedBefore(null);

    List<org.hisp.dhis.dxf2.events.trackedentity.TrackedEntityInstance> fetchedTeis =
            subject.getTrackedEntityInstances(queryParams, TrackedEntityInstanceParams.DATA_SYNCHRONIZATION, true, true);
    System.out.println("shouldNotSynchronizeDataWithSkipSynchronizationFlag");
    System.out.println(fetchedTeis);

    final Map<String, Set<String>> psdeSkipMap =
            programStageDataElementService
                    .getProgramStageDataElementsWithSkipSynchronizationSetToTrue();
    System.out.println("psdeSkipMap: " + psdeSkipMap);
    TrackedEntityInstances teis = new TrackedEntityInstances();
    teis.setTrackedEntityInstances(fetchedTeis);
    System.out.println("teis: " + teis);

    teis.getTrackedEntityInstances().forEach(tei -> {
        System.out.println("TEI UID: " + tei.getTrackedEntityInstance());
      tei.getEnrollments().forEach(enrollment -> {
        System.out.println("Enrollment UID: " + enrollment.getEnrollment());
        Enrollment enr = enrollmentService.getEnrollment(enrollment.getEnrollment(), EnrollmentParams.FALSE);
        System.out.println("enr(FALSE): " + enr);
        enr = enrollmentService.getEnrollment(enrollment.getEnrollment(), EnrollmentParams.TRUE);
        // Fails here with: Cannot invoke "org.hisp.dhis.user.User.getUid()" because "user" is null
        System.out.println("enr(TRUE): " + enr);
        enr.getEvents().forEach(event -> {
          System.out.println("Event UID: " + event.getEvent());
          event.getDataValues().forEach(dataValue -> {
            System.out.println(dataValue.getDataElement() + " : " + dataValue.getValue());
            if (dataValue.getValue().equals("Skip Sync")) {
              throw new RuntimeException("DataElement with skipSynchronization flag should not be synchronized");
            }
          });
        });
      });
    });

    assertTrue(true);
  }

  private org.hisp.dhis.dxf2.events.trackedentity.TrackedEntityInstance getTeiByUid(
      List<org.hisp.dhis.dxf2.events.trackedentity.TrackedEntityInstance> teis, String teiUid) {
    return teis.stream()
        .filter(t -> Objects.equals(t.getTrackedEntityInstance(), teiUid))
        .findAny()
        .get();
  }
}
