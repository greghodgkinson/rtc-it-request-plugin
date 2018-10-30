package com.greghodgkinson.rtcautomation;

import java.net.URI;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;

import com.ibm.team.links.common.IItemReference;
import com.ibm.team.links.common.ILink;
import com.ibm.team.links.common.IReference;
import com.ibm.team.links.common.factory.IReferenceFactory;
import com.ibm.team.links.common.registry.IEndPointDescriptor;
import com.ibm.team.links.common.registry.ILinkTypeRegistry;
import com.ibm.team.process.common.IDevelopmentLine;
import com.ibm.team.process.common.IIterationHandle;
import com.ibm.team.process.common.IProcessConfigurationElement;
import com.ibm.team.process.common.IProjectAreaHandle;
import com.ibm.team.process.common.advice.AdvisableOperation;
import com.ibm.team.process.common.advice.IAdvisorInfo;
import com.ibm.team.process.common.advice.IProcessReport;
import com.ibm.team.process.common.advice.IReportInfo;
import com.ibm.team.process.common.advice.runtime.IOperationParticipant;
import com.ibm.team.process.common.advice.runtime.IParticipantInfoCollector;
import com.ibm.team.repository.common.IItem;
import com.ibm.team.repository.common.Location;
import com.ibm.team.repository.common.TeamRepositoryException;
import com.ibm.team.repository.service.AbstractService;
import com.ibm.team.workitem.common.ISaveParameter;
import com.ibm.team.workitem.common.IWorkItemCommon;
import com.ibm.team.workitem.common.internal.WorkItemCommon;
import com.ibm.team.workitem.common.internal.model.WorkItem;
import com.ibm.team.workitem.common.model.IAttribute;
import com.ibm.team.workitem.common.model.IAttributeHandle;
import com.ibm.team.workitem.common.model.ICategoryHandle;
import com.ibm.team.workitem.common.model.IEnumeration;
import com.ibm.team.workitem.common.model.ILiteral;
import com.ibm.team.workitem.common.model.IWorkItem;
import com.ibm.team.workitem.common.model.IWorkItemHandle;
import com.ibm.team.workitem.common.model.IWorkItemReferences;
import com.ibm.team.workitem.common.model.ItemProfile;
import com.ibm.team.workitem.common.model.WorkItemEndPoints;
import com.ibm.team.workitem.common.model.WorkItemLinkTypes;
import com.ibm.team.workitem.service.IWorkItemServer;

import com.ibm.team.process.common.advice.IAdvisorInfoCollector;
import com.ibm.team.process.common.advice.runtime.IOperationAdvisor;
import com.ibm.team.process.internal.common.DevelopmentLine;
import com.ibm.team.repository.common.IAuditable;
import com.ibm.team.repository.common.util.NLS;
import com.ibm.team.workitem.common.model.IState;
import com.ibm.team.workitem.common.model.Identifier;
import com.ibm.team.workitem.common.workflow.IWorkflowInfo;

abstract class RtcAutomationAbstractService extends AbstractService {

	// Services we need
	protected IWorkItemServer fWorkItemServer;
	protected IWorkItemCommon fWorkItemCommon;

	final static String UPDATE_PRD_DELIVERY_DATE = "UPDATING_PRD_DELIVERY_DATE";
	final static String UPDATE_WORKABLE_DATE = "UPDATING_WORKABLE_DATE";
	final static String UPDATE_LINKS = "UPDATING_LINKS";
	final static String UPDATE_STATUS = "UPDATING_STATUS";
	final static String CASCADE_INVALIDATE = "CASCADING_INVALIDATE";
	final static String UPDATE_PRIORITY = "UPDATING_PRIORITY";
	final static String UPDATE_SUMMARY = "UPDATING_SUMMARY";
	final static String CREATE_CLONE = "CREATING_CLONE";

	final static List<String> ALLOWED_PRIMARY_DOMAINS_TO_MOVE = new ArrayList<String>() {
		{
			add("Portal");
			add("PBM");
		}
	};

	final static String AGILE_PROJECT_AREA = "Agile Transformation Program";

	final static String AGILE_CATEGORY_ROOT = "Agile Transformation Program/";

	final static Set<String> UPDATE_WORKABLE_DATE_PARAM = new HashSet<String>(
			new ArrayList<String>(Arrays.asList(UPDATE_WORKABLE_DATE)));
	
	final static Set<String> UPDATE_PRD_DELIVERY_DATE_PARAM = new HashSet<String>(
			new ArrayList<String>(Arrays.asList(UPDATE_PRD_DELIVERY_DATE)));

	final static Set<String> UPDATE_LINKS_PARAM = new HashSet<String>(
			new ArrayList<String>(Arrays.asList(UPDATE_LINKS)));

	final static Set<String> UPDATE_SUMMARY_PARAM = new HashSet<String>(
			new ArrayList<String>(Arrays.asList(UPDATE_SUMMARY)));

	final static Set<String> UPDATE_STATUS_PARAM = new HashSet<String>(
			new ArrayList<String>(Arrays.asList(UPDATE_STATUS)));

	final static Set<String> UPDATE_PRIORITY_PARAM = new HashSet<String>(
			new ArrayList<String>(Arrays.asList(UPDATE_PRIORITY)));

	final static Set<String> CREATE_CLONE_PARAM = new HashSet<String>(
			new ArrayList<String>(Arrays.asList(CREATE_CLONE)));

	public static final IEndPointDescriptor AFFECTS_PLAN_ITEM_ENDPOINT = ILinkTypeRegistry.INSTANCE
			.getLinkType(WorkItemLinkTypes.AFFECTS_PLAN_ITEM)
			.getTargetEndPointDescriptor();

	final static String TASK_STATUS_DONE = "com.ibm.team.workitem.taskWorkflow.state.s3";

	final static String TASK_STATUS_INVALID = "com.ibm.team.workitem.taskWorkflow.state.s4";

	final static String TASK_STATUS_INVALIDATE_SUGGESTED = "com.ibm.team.workitem.taskWorkflow.state.s5";

	final static String DEFECT_STATUS_IN_PROGRESS = "com.ibm.team.workitem.defectWorkflow.state.s2";

	final static String DEFECT_STATUS_RESOLVED = "com.ibm.team.workitem.defectWorkflow.state.s3";

	final static String DEFECT_STATUS_VERIFIED = "com.ibm.team.workitem.defectWorkflow.state.s4";

	final static String STORY_STATUS_IN_PROGRESS = "com.ibm.team.apt.story.defined";

	final static String STORY_STATUS_DONE = "com.ibm.team.apt.story.verified";

	final static String STORY_STATUS_INVALID = "com.ibm.team.apt.storyWorkflow.state.s2";

	final static String STORY_STATUS_IMPLEMENTED = "com.ibm.team.apt.story.tested";

	final static String STORY_WI_TYPE = "com.ibm.team.apt.workItemType.story";
	
	final static String TICKET_WI_TYPE = "greghodgkinson_it_request";

	final static String TICKET_STATUS_DRAFT = "com.greghodgkinson.workitems.workflow.itRequest.state.s6";

	final static String TICKET_STATUS_SUBMITTED = "com.greghodgkinson.workitems.workflow.itRequest.state.s4";

	final static String TICKET_STATUS_READY_FOR_DEV = "com.greghodgkinson.workitems.workflow.itRequest.state.s8";

	final static String TICKET_STATUS_IN_PROGRESS = "com.greghodgkinson.workitems.workflow.itRequest.state.s3";

	final static String TICKET_STATUS_IMPLEMENTED = "com.greghodgkinson.workitems.workflow.itRequest.state.s2";

	final static String TICKET_STATUS_DONE = "com.greghodgkinson.workitems.workflow.itRequest.state.s1";

	final static String TICKET_STATUS_INVALID = "com.greghodgkinson.workitems.workflow.itRequest.state.s5";

	final static String TICKET_RESOLUTION_MOVED_TO_AGILE_BACKLOG = "com.greghodgkinson.workitems.workflow.itRequest.resolution.r10";

	final static String TASK_NAME_GO_NOGO = "Go/No-Go Review";

	final static String TASK_NAME_IT_SOL_AGREE = "IT Solution Agreement";

	final static String TASK_NAME_DESIGN_AND_DEV_SOL = "Design & Develop Solution";

	final static String TASK_NAME_PROD_TEST = "Prod Test";

	protected IWorkItemHandle findParentHandle(ISaveParameter saveParameter,
			IProgressMonitor monitor) throws TeamRepositoryException {

		// Check to see if the references contain a 'Parent' link
		List<IReference> references = saveParameter.getNewReferences()
				.getReferences(WorkItemEndPoints.PARENT_WORK_ITEM);
		if (references.isEmpty())
			return null;

		// Traverse the list of references (there should only be 1 parent) and
		// ensure the reference is to a work item then return a handle to that
		// work item
		for (IReference reference : references)
			if (reference.isItemReference()
					&& ((IItemReference) reference).getReferencedItem() instanceof IWorkItemHandle)
				return (IWorkItemHandle) ((IItemReference) reference)
						.getReferencedItem();

		return null;
	}

	protected IWorkItem getExistingParentWorkItem(IWorkItem workItem,
			IProgressMonitor monitor) throws TeamRepositoryException {

		IWorkItemReferences workItemExistingReferences = fWorkItemServer
				.resolveWorkItemReferences(workItem, monitor);

		// Check to see if the references contain a 'Parent' link
		List<IReference> references = workItemExistingReferences
				.getReferences(WorkItemEndPoints.PARENT_WORK_ITEM);
		if (references.isEmpty())
			return null;

		// Traverse the list of references (there should only be 1 parent) and
		// ensure the reference is to a work item then return a handle to that
		// work item
		for (IReference reference : references)
			if (reference.isItemReference()
					&& ((IItemReference) reference).getReferencedItem() instanceof IWorkItemHandle) {

				IWorkItemHandle parentHandle = (IWorkItemHandle) ((IItemReference) reference)
						.getReferencedItem();

				IWorkItem parent = (IWorkItem) fWorkItemServer
						.getAuditableCommon()
						.resolveAuditable(parentHandle, IWorkItem.FULL_PROFILE,
								monitor).getWorkingCopy();

				return parent;

			}

		return null;
	}

	protected IWorkItem getNewOrExistingParentWorkItem(IWorkItem workItem,
			IWorkItemReferences workItemExistingReferences,
			IWorkItemReferences workItemNewReferences, IReference thisEndPoint,
			IProgressMonitor monitor) throws TeamRepositoryException {

		// Check to see if the references contain a 'Parent' link
		List<IReference> references = narrowAndCombineLists(
				workItemExistingReferences, workItemNewReferences,
				thisEndPoint, WorkItemEndPoints.PARENT_WORK_ITEM, monitor);
		if (references.isEmpty())
			return null;

		// Traverse the list of references (there should only be 1 parent) and
		// ensure the reference is to a work item then return a handle to that
		// work item
		for (IReference reference : references)
			if (reference.isItemReference()
					&& ((IItemReference) reference).getReferencedItem() instanceof IWorkItemHandle) {

				IWorkItemHandle parentHandle = (IWorkItemHandle) ((IItemReference) reference)
						.getReferencedItem();

				IWorkItem parent = (IWorkItem) fWorkItemServer
						.getAuditableCommon()
						.resolveAuditable(parentHandle, IWorkItem.FULL_PROFILE,
								monitor).getWorkingCopy();

				return parent;

			}

		return null;
	}

	protected IWorkItem getWorkItemFromReference(IReference reference,
			IReference thisEndPoint, IProgressMonitor monitor)
			throws TeamRepositoryException {

		IWorkItem workItem = null;

		IReference otherReference = reference.getLink().getOtherRef(
				thisEndPoint);

		if (otherReference.isItemReference()) {

			workItem = (IWorkItem) fWorkItemServer.getAuditableCommon()
					.resolveAuditable(
							(IWorkItemHandle) reference.getLink()
									.getOtherRef(thisEndPoint).resolve(),
							IWorkItem.FULL_PROFILE, monitor);

		} else {

			URI uri = reference.createURI();

			// get the location from the URI
			Location location = Location.location(uri);
			// resolve the item by location
			IAuditable referenced = fWorkItemServer.getAuditableCommon()
					.resolveAuditableByLocation(
							location,
							ItemProfile.createFullProfile(location
									.getItemType()), null);
			// look for a referenced work item
			if (referenced instanceof IWorkItem) {
				workItem = (IWorkItem) referenced;
			}

		}

		return workItem;
	}

	protected Boolean isSameWorkItem(IWorkItem child1WorkItem,
			IWorkItem child2WorkItem) {

		Integer id1 = child1WorkItem.getId();
		Integer id2 = child2WorkItem.getId();

		return (child1WorkItem.getId() == child2WorkItem.getId());
	}

	protected List combineReferenceLists(List<IReference> list1,
			List<IReference> list2, IReference thisEndPoint,
			IProgressMonitor monitor) throws TeamRepositoryException {

		List combinedList = new ArrayList();

		combinedList.addAll(list1);

		if (list2 != null) {

			for (IReference reference2 : list2) {

				IWorkItem child2WorkItem = getWorkItemFromReference(reference2,
						thisEndPoint, monitor);

				Boolean addToList = true;

				for (IReference reference1 : list1) {

					IWorkItem child1WorkItem = getWorkItemFromReference(
							reference1, thisEndPoint, monitor);

					addToList = isSameWorkItem(child1WorkItem, child2WorkItem);
				}

				if (addToList) {

					combinedList.add(reference2);

				}

			}

		}

		return combinedList;

	}

	protected List narrowAndCombineLists(
			IWorkItemReferences existingReferences,
			IWorkItemReferences newReferences, IReference thisEndPoint,
			IEndPointDescriptor narrowToDescriptor, IProgressMonitor monitor)
			throws TeamRepositoryException {

		// Narrow down lists based on descriptor

		List<IReference> existingList = narrowList(existingReferences,
				narrowToDescriptor);

		List<IReference> newList = narrowList(newReferences, narrowToDescriptor);

		List combinedList = combineReferenceLists(existingList, newList,
				thisEndPoint, monitor);

		return combinedList;

	}

	protected List narrowList(IWorkItemReferences references,
			IEndPointDescriptor narrowToDescriptor) {

		List<IReference> list = references.getReferences(narrowToDescriptor);

		return list;

	}

	protected ILiteral getLiteralbyID(Identifier<ILiteral> findLliteralID,
			IAttributeHandle iAttribute) throws TeamRepositoryException {

		IEnumeration enumeration = fWorkItemCommon.resolveEnumeration(
				iAttribute, null);

		List<ILiteral> literals = enumeration.getEnumerationLiterals();
		for (Iterator<ILiteral> iterator = literals.iterator(); iterator
				.hasNext();) {
			ILiteral iLiteral = (ILiteral) iterator.next();
			if (iLiteral.getIdentifier2().equals(findLliteralID)) {
				return iLiteral;
			}
		}
		return null;
	}

	protected Boolean isAllDependsOnClosed(IWorkItem workItem,
			IProgressMonitor monitor) throws TeamRepositoryException {

		return (isAllLinkedClosed(workItem, WorkItemEndPoints.DEPENDS_ON_WORK_ITEM, monitor));

	}
	
	protected Boolean isAllBlocksOpen(IWorkItem workItem,
			IProgressMonitor monitor) throws TeamRepositoryException {

		return (isAllLinkedOpen(workItem, WorkItemEndPoints.BLOCKS_WORK_ITEM, monitor));

	}
	
	protected Boolean isAllLinkedClosed(IWorkItem workItem, IEndPointDescriptor endPointDescriptor,
			IProgressMonitor monitor) throws TeamRepositoryException {

		Boolean isAllLinkedClosed = true;

		IReference thisEndPoint = getEndPointReference(workItem);

		List existingLinksList = getExistingReferences(workItem,
				thisEndPoint, endPointDescriptor, monitor);

		// Iterate through depends on work items

		for (Iterator iterator = existingLinksList.iterator(); iterator
				.hasNext();) {

			IReference iReference = (IReference) iterator.next();

			// Get link from reference

			ILink link = iReference.getLink();

			IEndPointDescriptor thisEndPointDescriptor = link
					.getThisEndpointDescriptor(thisEndPoint);
			IEndPointDescriptor otherEndPointDescriptor = link
					.getOtherEndpointDescriptor(thisEndPoint);

			if (otherEndPointDescriptor == endPointDescriptor) {

				IWorkItem dependsOnWorkItem = (IWorkItem) fWorkItemServer
						.getAuditableCommon().resolveAuditable(
								(IWorkItemHandle) link
										.getOtherRef(thisEndPoint).resolve(),
								IWorkItem.FULL_PROFILE, monitor);

				if (!isWorkItemClosed(dependsOnWorkItem)) {

					isAllLinkedClosed = false;

				}

			}
		}

		return isAllLinkedClosed;

	}
	
	protected Boolean isAllLinkedOpen(IWorkItem workItem, IEndPointDescriptor endPointDescriptor,
			IProgressMonitor monitor) throws TeamRepositoryException {

		Boolean isAllLinkedOpen = true;

		IReference thisEndPoint = getEndPointReference(workItem);

		List existingLinksList = getExistingReferences(workItem,
				thisEndPoint, endPointDescriptor, monitor);

		// Iterate through depends on work items

		for (Iterator iterator = existingLinksList.iterator(); iterator
				.hasNext();) {

			IReference iReference = (IReference) iterator.next();

			// Get link from reference

			ILink link = iReference.getLink();

			IEndPointDescriptor thisEndPointDescriptor = link
					.getThisEndpointDescriptor(thisEndPoint);
			IEndPointDescriptor otherEndPointDescriptor = link
					.getOtherEndpointDescriptor(thisEndPoint);

			if (otherEndPointDescriptor == endPointDescriptor) {

				IWorkItem dependsOnWorkItem = (IWorkItem) fWorkItemServer
						.getAuditableCommon().resolveAuditable(
								(IWorkItemHandle) link
										.getOtherRef(thisEndPoint).resolve(),
								IWorkItem.FULL_PROFILE, monitor);

				if (isWorkItemClosed(dependsOnWorkItem)) {

					isAllLinkedOpen = false;

				}

			}
		}

		return isAllLinkedOpen;

	}

	protected IReference getEndPointReference(IWorkItem workItem) {
		IReference thisEndPoint = IReferenceFactory.INSTANCE
				.createReferenceToItem(workItem);
		return thisEndPoint;
	}

	protected List getExistingReferences(IWorkItem workItem,
			IReference thisEndPoint, IEndPointDescriptor endPointDescriptor,
			IProgressMonitor monitor) throws TeamRepositoryException {

		// Get work item references as
		// well as the reference to this end point

		IWorkItemReferences existingReferences = fWorkItemServer
				.resolveWorkItemReferences(workItem, monitor);

		// Narrow down to depends on work items

		List existingList = narrowList(existingReferences, endPointDescriptor);

		return existingList;
	}

	protected Boolean isWorkItemCreatedUsingTemplate(IWorkItem workItem,
			IProgressMonitor monitor) throws TeamRepositoryException {

		IAttribute createdUsingTemplateAttribute = fWorkItemCommon
				.findAttribute(workItem.getProjectArea(),
						"createdUsingTemplate", monitor);

		// Always first check if attributes exist
		if (createdUsingTemplateAttribute != null
				&& workItem.hasAttribute(createdUsingTemplateAttribute)) {

			return (Boolean) getAttributeValue(workItem,
					"createdUsingTemplate", monitor);

		} else {

			return false;
		}

	}

	protected Boolean isWorkflowStateChange(IWorkItem newState,
			IWorkItem oldState) {

		if (newState != null && oldState != null) {

			return !(newState.getState2().getStringIdentifier().equals(oldState
					.getState2().getStringIdentifier()));

		} else
			return false;

	}

	protected Boolean isPriorityChange(IWorkItem newState, IWorkItem oldState) {

		if (newState != null && oldState != null) {

			return !(newState.getPriority().getStringIdentifier()
					.equals(oldState.getPriority().getStringIdentifier()));

		} else
			return false;

	}

	protected Boolean isWorkItemClosed(IWorkItem workItem)
			throws TeamRepositoryException {

		Identifier<IState> status = workItem.getState2();

		String statusString = status.getStringIdentifier();

		return (statusString.equals(TASK_STATUS_DONE) // Done
				|| statusString.equals(TASK_STATUS_INVALID) // Invalid
				|| statusString.equals(DEFECT_STATUS_RESOLVED) // Resolved
				|| statusString.equals(DEFECT_STATUS_VERIFIED) // Verified
				|| statusString.equals(STORY_STATUS_DONE) // Done
				|| statusString.equals(STORY_STATUS_INVALID) // Invalid
		|| statusString.equals(STORY_STATUS_IMPLEMENTED) // Implemented
		);
	}

	protected Boolean isWorkItemInvalidated(IWorkItem workItem)
			throws TeamRepositoryException {

		Identifier<IState> status = workItem.getState2();

		String statusString = status.getStringIdentifier();

		return (statusString.equals(TASK_STATUS_INVALID) || statusString
				.equals(STORY_STATUS_INVALID)
				| statusString.equals(TICKET_STATUS_INVALID));
	}

	protected Timestamp getWorkableDate(IWorkItem workItem,
			IProgressMonitor monitor) throws TeamRepositoryException {

		IAttribute workableDateAttribute = fWorkItemCommon.findAttribute(
				workItem.getProjectArea(), "workableDate", monitor);

		// Always first check if attributes exist
		if (workableDateAttribute != null
				&& workItem.hasAttribute(workableDateAttribute)) {

			return (Timestamp) getAttributeValue(workItem, "workableDate",
					monitor);

		} else {
			return null;
		}

	}

	protected Boolean isTaskStoryOrDefect(IWorkItem workItem) {

		String type = workItem.getWorkItemType();

		return (type.equals("task")
				|| type.equals("com.ibm.team.apt.workItemType.story") || type
					.equals("defect"));
	}

	protected Boolean isStoryOrDefect(IWorkItem workItem) {

		String type = workItem.getWorkItemType();

		return (type.equals("com.ibm.team.apt.workItemType.story") || type
				.equals("defect"));
	}

	protected Boolean isTask(IWorkItem workItem) {

		String type = workItem.getWorkItemType();

		return (type.equals("task"));
	}

	protected boolean isDefect(IWorkItem workItem) {

		String type = workItem.getWorkItemType();

		return (type.equals("defect"));
	}

	protected boolean isStory(IWorkItem workItem) {

		String type = workItem.getWorkItemType();

		return (type.equals("com.ibm.team.apt.workItemType.story"));
	}

	protected Boolean isItRequest(IWorkItem workItem) {

		String type = workItem.getWorkItemType();

		return (type.equals("greghodgkinson_it_request"));
	}

	protected Object getAttributeValue(IWorkItem workItem,
			String attributeName, IProgressMonitor monitor)
			throws TeamRepositoryException {

		IAttribute attribute = fWorkItemCommon.findAttribute(
				workItem.getProjectArea(), attributeName, monitor);

		if (workItem.hasAttribute(attribute)) {
			return workItem.getValue(attribute);
		} else {
			return null;
		}

	}

	protected String getAttributeDisplayValue(IWorkItem workItem,
			String attributeName, IProgressMonitor monitor)
			throws TeamRepositoryException {

		String attributeDisplayValue = null;

		IAttribute attribute = fWorkItemCommon.findAttribute(
				workItem.getProjectArea(), attributeName, monitor);

		if (workItem.hasAttribute(attribute)) {
			
			Object object =workItem.getValue(attribute);

			if (workItem.getValue(attribute) instanceof Identifier) {

				Identifier attributeLiteralID = (Identifier) workItem
						.getValue(attribute);
				ILiteral attributeLiteral = getLiteralbyID(attributeLiteralID,
						attribute);

				attributeDisplayValue = attributeLiteral.getName();

			}

		}
		return attributeDisplayValue;

	}
	
	protected Timestamp getAttributeTimestamp(IWorkItem workItem,
			String attributeName, IProgressMonitor monitor)
			throws TeamRepositoryException {

		Timestamp attributeTimestamp = null;

		IAttribute attribute = fWorkItemCommon.findAttribute(
				workItem.getProjectArea(), attributeName, monitor);

		if (workItem.hasAttribute(attribute)) {
			
			Object object = workItem.getValue(attribute);

			if (workItem.getValue(attribute) instanceof Timestamp) {

				attributeTimestamp = (Timestamp) workItem.getValue(attribute);

			}

		}
		return attributeTimestamp;

	}

	protected Boolean isStateChange(IWorkItem newWI, IWorkItem oldWI) {

		String newState = newWI.getState2().getStringIdentifier();
		String oldState = oldWI.getState2().getStringIdentifier();

		return (!newState.equals(oldState));
	}

	protected Identifier determineActionForState(IWorkItem workItem,
			String targetState, IProgressMonitor monitor)
			throws TeamRepositoryException {

		IWorkflowInfo workflowInfo = fWorkItemServer.findWorkflowInfo(workItem,
				monitor);

		Identifier actionId = null;

		if (workflowInfo != null) {

			// Get the available actions to take from the current state
			Identifier availableActions[] = workflowInfo.getActionIds(workItem
					.getState2());

			// For each possible action, determine the end state, and check if
			// it is the one we want

			for (int i = 0; i < availableActions.length; i++) {

				Identifier<IState> resultState = workflowInfo
						.getActionResultState(availableActions[i]);

				if (resultState.getStringIdentifier().equals(targetState)) {

					actionId = availableActions[i];
					break;
				}

			}

		}

		return actionId;

	}

	protected boolean isStatus(IWorkItem workItem, String status) {

		return (workItem.getState2().getStringIdentifier().equals(status));
	}

	protected void updateStatusAndSave(IWorkItem workItem, String status,
			Set<String> additionalSaveParameters, IProgressMonitor monitor,
			IParticipantInfoCollector collector) throws TeamRepositoryException {

		IWorkItem workingCopy = (IWorkItem) fWorkItemServer
				.getAuditableCommon()
				.resolveAuditable(workItem, IWorkItem.FULL_PROFILE, monitor)
				.getWorkingCopy();
		
		if (!isStatus(workingCopy, status)) {
			
			Identifier actionId = determineActionForState(workItem, status, monitor);

			IStatus saveStatus = fWorkItemServer.saveWorkItem3(workingCopy, null,
					actionId.getStringIdentifier(), additionalSaveParameters);

			if (!saveStatus.isOK()) {

				createError("Unable to save the work item '" + workItem.getItemId()
						+ "'.", "Unable to set work item status.", collector);
			}
		}
	}

	protected void createError(String errorDescription, String errorInfo,
			IParticipantInfoCollector collector) {

		IReportInfo info = collector.createInfo(errorInfo, errorDescription);
		info.setSeverity(IProcessReport.ERROR);
		collector.addInfo(info);
	}
	
	protected void createError(Throwable errorDescription, String errorInfo,
			IAdvisorInfoCollector collector) {

		IReportInfo info = collector.createExceptionInfo(errorInfo, errorDescription);
		info.setSeverity(IProcessReport.ERROR);
		collector.addInfo(info);
	}

	protected List getExistingReferences(IWorkItem workItem,
			IEndPointDescriptor endPointDescriptor, IProgressMonitor monitor)
			throws TeamRepositoryException {

		IReference workItemEndPoint = getEndPointReference(workItem);

		return getExistingReferences(workItem, workItemEndPoint,
				endPointDescriptor, monitor);

	}
	
	protected Timestamp getChildWorkableDate(IWorkItem workItem, String childSummary,
			IProgressMonitor monitor) throws TeamRepositoryException {

		IWorkItem child = getChild(workItem, childSummary, monitor);
		
		return getWorkableDate(child, monitor);
		
	}

	protected IWorkItem getChild(IWorkItem workItem, String childSummary,
			IProgressMonitor monitor) throws TeamRepositoryException {

		IWorkItem child = null;
		
		List existingChildren = getExistingReferences(workItem,
				WorkItemEndPoints.CHILD_WORK_ITEMS, monitor);	
		
		for (Iterator iterator = existingChildren.iterator(); iterator
				.hasNext();) {
			
			IReference iReference = (IReference) iterator.next();

			// Get link from reference

			ILink link = iReference.getLink();

			IWorkItem aChild = getWorkItemFromReference(iReference,
					workItem, monitor);
			
			if (aChild.getHTMLSummary().getPlainText().equals(childSummary)) {
				
				child = aChild;
				
			}
		}
		return child;		
	}
	
	protected IWorkItem getWorkItemFromReference(IReference iReference,
			IWorkItem workItem, IProgressMonitor monitor)
			throws TeamRepositoryException {

		return getWorkItemFromReference(iReference,
				getEndPointReference(workItem), monitor);
	}
	
	protected IWorkItem getParent(IWorkItem workItem, 
			IProgressMonitor monitor) throws TeamRepositoryException {

		IWorkItem parent = null;
		
		List existingParents = getExistingReferences(workItem,
				WorkItemEndPoints.PARENT_WORK_ITEM, monitor);
		
		for (Iterator iterator = existingParents.iterator(); iterator
				.hasNext();) {
			
			IReference iReference = (IReference) iterator.next();

			// Get link from reference

			ILink link = iReference.getLink();

			IWorkItem aParent = getWorkItemFromReference(iReference,
					workItem, monitor);
			
			parent = aParent;
		}
		
		return parent;		
	}

	protected boolean isAttributeBeingSet(IWorkItem workItem,
			IWorkItem oldState, String attributeName, IProgressMonitor monitor) throws TeamRepositoryException {
	
		String newValue = getAttributeDisplayValue(workItem, attributeName, monitor);
		
		String oldValue = getAttributeDisplayValue(oldState, attributeName, monitor);

		return ((newValue != null && oldValue == null) || !newValue.equals(oldValue));
	}
	
	protected boolean isAttributeBeingUnset(IWorkItem workItem,
			IWorkItem oldState, String attributeName, IProgressMonitor monitor) throws TeamRepositoryException {
	
		String newValue = getAttributeDisplayValue(workItem, attributeName, monitor);
		
		String oldValue = getAttributeDisplayValue(oldState, attributeName, monitor);

		return ((newValue == null && oldValue != null));
	}
	
	protected boolean isAttributeEqualTo(IWorkItem workItem,
			String attributeName, String value, IProgressMonitor monitor) throws TeamRepositoryException {
	
		String currentValue = getAttributeDisplayValue(workItem, attributeName, monitor);

		return ((currentValue == null && value == null) || value.equals(currentValue));
	}
	
}
