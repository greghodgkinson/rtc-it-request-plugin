package com.greghodgkinson.rtcautomation;

import java.net.URI;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.HashSet;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;

import com.ibm.team.foundation.common.text.XMLString;
import com.ibm.team.links.common.IItemReference;
import com.ibm.team.links.common.ILink;
import com.ibm.team.links.common.IReference;
import com.ibm.team.links.common.factory.IReferenceFactory;
import com.ibm.team.links.common.registry.IEndPointDescriptor;
import com.ibm.team.links.common.registry.ILinkTypeRegistry;
import com.ibm.team.process.common.IIterationHandle;
import com.ibm.team.process.common.IProcessArea;
import com.ibm.team.process.common.IProcessConfigurationElement;
import com.ibm.team.process.common.IProjectArea;
import com.ibm.team.process.common.IProjectAreaHandle;
import com.ibm.team.process.common.advice.AdvisableOperation;
import com.ibm.team.process.common.advice.IAdvisorInfo;
import com.ibm.team.process.common.advice.IProcessReport;
import com.ibm.team.process.common.advice.IReportInfo;
import com.ibm.team.repository.common.IContributorHandle;
import com.ibm.team.repository.common.Location;
import com.ibm.team.repository.common.TeamRepositoryException;
import com.ibm.team.workitem.common.IAuditableCommon;
import com.ibm.team.workitem.common.ISaveParameter;
import com.ibm.team.workitem.common.IWorkItemCommon;
import com.ibm.team.workitem.common.internal.model.WorkItem;
import com.ibm.team.workitem.common.internal.model.WorkItemReferences;
import com.ibm.team.workitem.common.internal.model.WorkItemType;
import com.ibm.team.workitem.common.model.IApproval;
import com.ibm.team.workitem.common.model.IApprovals;
import com.ibm.team.workitem.common.model.IAttribute;
import com.ibm.team.workitem.common.model.ICategoryHandle;
import com.ibm.team.workitem.common.model.ILiteral;
import com.ibm.team.workitem.common.model.IPriority;
import com.ibm.team.workitem.common.model.IResolution;
import com.ibm.team.workitem.common.model.ISubscriptions;
import com.ibm.team.workitem.common.model.IWorkItem;
import com.ibm.team.workitem.common.model.IWorkItemHandle;
import com.ibm.team.workitem.common.model.IWorkItemReferences;
import com.ibm.team.workitem.common.model.IWorkItemType;
import com.ibm.team.workitem.common.model.ItemProfile;
import com.ibm.team.workitem.common.model.WorkItemEndPoints;
import com.ibm.team.workitem.common.model.WorkItemLinkTypes;
import com.ibm.team.workitem.service.IWorkItemServer;
import com.ibm.team.workitem.service.IWorkItemWrapper;

import com.ibm.team.process.common.advice.IAdvisorInfoCollector;
import com.ibm.team.process.common.advice.runtime.IOperationAdvisor;
import com.ibm.team.process.common.advice.runtime.IOperationParticipant;
import com.ibm.team.process.common.advice.runtime.IParticipantInfoCollector;
import com.ibm.team.process.internal.common.ProjectAreaHandle;
import com.ibm.team.repository.common.IAuditable;
import com.ibm.team.repository.common.util.NLS;
import com.ibm.team.workitem.common.model.IState;
import com.ibm.team.workitem.common.model.Identifier;
import com.ibm.team.workitem.common.workflow.IWorkflowInfo;

public class WorkflowManagerParticipant extends RtcAutomationAbstractService
		implements IOperationParticipant {

	public void run(AdvisableOperation operation,
			IProcessConfigurationElement participantConfig,
			IParticipantInfoCollector collector, IProgressMonitor monitor)
			throws TeamRepositoryException {

		Object data = operation.getOperationData();
		ISaveParameter saveParameter = null;

		// Only process work item saves

		if (data instanceof ISaveParameter) {

			saveParameter = (ISaveParameter) data;

			IAuditable newState = saveParameter.getNewState();

			// Initialize required service interfaces

			fWorkItemServer = getService(IWorkItemServer.class);
			fWorkItemCommon = getService(IWorkItemCommon.class);

			// Only process if new state is a work item

			if (newState instanceof IWorkItem) {

				IWorkItem workItem = (IWorkItem) newState;

				String summary = workItem.getHTMLSummary().getPlainText();

				Set<String> additionalSaveParams = saveParameter
						.getAdditionalSaveParameters();

				// If there is no recursion...

				if (additionalSaveParams == null
						|| (additionalSaveParams != null
								&& !additionalSaveParams
										.contains(UPDATE_WORKABLE_DATE)								
								&& !additionalSaveParams
										.contains(UPDATE_SUMMARY) && !additionalSaveParams
									.contains(CASCADE_INVALIDATE))) {

					IWorkItem oldState = (IWorkItem) saveParameter
							.getOldState();

					// Process saves of one of the various work item types we
					// care about...

					if (isTaskStoryOrDefect(workItem)) {

						processTaskStoryDefectNonRecursionSave(workItem,
								oldState, saveParameter, collector, monitor);

					} else if (isItRequest(workItem)) {

						processItRequestNonRecursionSave(workItem, oldState,
								saveParameter, participantConfig, collector,
								monitor);

					}

					// Else there is some recursion happening...

				} else {

					// Specifically for a workable date recursive save...

					if (saveParameter.getAdditionalSaveParameters() != null
							&& saveParameter.getAdditionalSaveParameters()
									.contains(UPDATE_WORKABLE_DATE)) {

						processWorkableDateSave(workItem, newState,
								saveParameter, collector, monitor);

					}
				}

				// Default values when creating new work item...

				if (saveParameter.isCreation()) {

					defaultValuesForNewWorkItemSave(workItem, summary,
							collector, monitor);

				}

			}
		}

	}

	private void defaultValuesForNewWorkItemSave(IWorkItem workItem,
			String summary, IParticipantInfoCollector collector,
			IProgressMonitor monitor) throws TeamRepositoryException {
		if (isWorkItemCreatedUsingTemplate(workItem, monitor)) {

			// For Go/No-Go review tasks, default the status to
			// invalidate suggested
			if (summary.equals(TASK_NAME_GO_NOGO)) {

				updateStatusAndSave(workItem, TASK_STATUS_INVALIDATE_SUGGESTED,
						UPDATE_STATUS_PARAM, monitor, collector);
			}

		}
	}

	private void processWorkableDateSave(IWorkItem workItem,
			IAuditable newState, ISaveParameter saveParameter,
			IParticipantInfoCollector collector, IProgressMonitor monitor)
			throws TeamRepositoryException {

		// If status is invalidate suggested and workable date set
		// then set status to invalid automatically

		if (workItem.getState2().getStringIdentifier()
				.equals(TASK_STATUS_INVALIDATE_SUGGESTED)) {

			// If workable date is set then automatically update
			// the
			// status to be invalidated

			if (getWorkableDate((IWorkItem) newState, monitor) != null) {

				updateStatusAndSave(workItem, TASK_STATUS_INVALID,
						UPDATE_STATUS_PARAM, monitor, collector);

			}

		}

		// If this is a task that has children, we might need to
		// make those children workable

		if (isTask(workItem)) {

			// Get all work item references (existing and new)
			// as
			// well as the refernce to this end point

			IWorkItemReferences workItemExistingReferences = fWorkItemServer
					.resolveWorkItemReferences(workItem, monitor);

			IWorkItemReferences workItemNewReferences = saveParameter
					.getNewReferences();

			IReference workItemEndPoint = IReferenceFactory.INSTANCE
					.createReferenceToItem(workItem);

			// Update workable date on all children...
			updateChildrenBasedOnUpstream(workItem, workItemExistingReferences,
					workItemNewReferences, workItemEndPoint, monitor, collector);

		}
	}

	private void processItRequestNonRecursionSave(IWorkItem workItem,
			IWorkItem oldState, ISaveParameter saveParameter,
			IProcessConfigurationElement participantConfig,
			IParticipantInfoCollector collector, IProgressMonitor monitor)
			throws TeamRepositoryException {

		// Recursively ripple priority changes to child work items...

		if (isPriorityChange(workItem, oldState)) {

			// Get all work item references (existing and new)
			// as well as the reference to this end point

			IWorkItemReferences existingReferences = fWorkItemServer
					.resolveWorkItemReferences(workItem, monitor);

			IWorkItemReferences newReferences = saveParameter
					.getNewReferences();

			IReference thisEndPoint = IReferenceFactory.INSTANCE
					.createReferenceToItem(workItem);

			// Update priority on all children
			updatePriorityChildWorkItems(workItem, existingReferences,
					newReferences, thisEndPoint, monitor, collector);

		}

		// Update workable date of all children if ticket status has been
		// changed to either submitted (potentially add workable date) or draft
		// (remove existing workable dates)...

		if (isStatusChangeToStatus(workItem, oldState, TICKET_STATUS_SUBMITTED)
				|| isStatusChangeToStatus(workItem, oldState,
						TICKET_STATUS_DRAFT)) {

			// Get all work item references (existing and new)
			// as well as the reference to this end point

			IWorkItemReferences workItemExistingReferences = fWorkItemServer
					.resolveWorkItemReferences(workItem, monitor);

			IWorkItemReferences workItemNewReferences = saveParameter
					.getNewReferences();

			IReference workItemEndPoint = IReferenceFactory.INSTANCE
					.createReferenceToItem(workItem);

			// Update workable date on all children

			updateChildrenBasedOnUpstream(workItem, workItemExistingReferences,
					workItemNewReferences, workItemEndPoint, monitor, collector);

		}

		// Clone story to agile backlog if invalidated and reason is 'move to
		// agile backlog'...

		if (isStatusChangeToStatus(workItem, oldState, TICKET_STATUS_INVALID)
				&& isResolution(workItem.getResolution2(),
						TICKET_RESOLUTION_MOVED_TO_AGILE_BACKLOG)) {

			ParsedConfig parsedConfig = new ParsedConfig();

			parseConfig(participantConfig, parsedConfig);

			// Get primary domain to use to set the category

			String primaryDomain = getAttributeDisplayValue(workItem,
					"functionalArea", monitor);

			String mappedSubcategory = null;

			for (Iterator iterator = parsedConfig.fDomainCategoryMappings
					.iterator(); iterator.hasNext();) {

				DomainCategoryMapping mapping = (DomainCategoryMapping) iterator
						.next();

				if (mapping.fPrimaryDomain.equals(primaryDomain)) {

					mappedSubcategory = mapping.fWISubcategory;

				}
			}

			if (mappedSubcategory != null) {

				// Create clone

				cloneWorkItem(workItem, STORY_WI_TYPE,
						parsedConfig.fAgileProjectArea,
						parsedConfig.fAgileCategoryRoot + mappedSubcategory,
						monitor);

			} else {

				createError("The primary domain '" + primaryDomain
						+ "' is not allowed on the agile backlog.",
						"The primary domain is not allowed on the backlog.",
						collector);

			}

		}

	}

	private ICategoryHandle getCategoryHandle(String categoryName,
			IProjectAreaHandle projectAreaHandle, IProgressMonitor monitor)
			throws TeamRepositoryException {

		List<String> namepath = Arrays.asList(categoryName.split("/"));

		ICategoryHandle categoryHandle = fWorkItemServer
				.findCategoryByNamePath(projectAreaHandle, namepath, monitor);
		return categoryHandle;
	}

	private IWorkItem cloneWorkItem(IWorkItem workItemToClone,
			String workItemType, String projectAreaName, String categoryName,
			IProgressMonitor monitor) throws TeamRepositoryException {

		IProjectAreaHandle projectAreaHandle = getProjectAreaHandle(
				projectAreaName, monitor);

		ICategoryHandle categoryHandle = getCategoryHandle(categoryName,
				projectAreaHandle, monitor);

		// Get a work item type handle for the project area and work item type
		// passed in

		IWorkItemType workItemTypeHandle = fWorkItemServer.findWorkItemType(
				projectAreaHandle, workItemType, monitor);

		IWorkItem newWorkItem = fWorkItemServer
				.createWorkItem2(workItemTypeHandle);

		// Set category to the one passed in

		newWorkItem.setCategory(categoryHandle);

		// Set summary and description to that of the work item passed in

		newWorkItem.setHTMLSummary(workItemToClone.getHTMLSummary());
		newWorkItem.setHTMLDescription(workItemToClone.getHTMLDescription());

		// Add subscriber based on the owner of the work item passed in

		IContributorHandle creatorHandle = workItemToClone.getCreator();

		ISubscriptions subscriptions = newWorkItem.getSubscriptions();

		subscriptions.add(creatorHandle);

		// Add link to work item passed in

		IReference workItemToCloneReference = getEndPointReference(workItemToClone);

		IWorkItemReferences newReferences = new WorkItemReferences(newWorkItem);

		newReferences.add(WorkItemEndPoints.COPIED_FROM_WORK_ITEM,
				workItemToCloneReference);

		fWorkItemServer.saveWorkItem3(newWorkItem, newReferences, null,
				CREATE_CLONE_PARAM);

		return newWorkItem;
	}

	private IProjectAreaHandle getProjectAreaHandle(String projectAreaName,
			IProgressMonitor monitor) throws TeamRepositoryException {

		URI areaUri = URI.create(projectAreaName.replaceAll(" ", "%20"));
		IProcessArea processArea = fWorkItemServer.getAuditableServer()
				.findProcessAreaByURI(areaUri,
						ItemProfile.PROJECT_AREA_DEFAULT, monitor);

		return processArea.getProjectArea();
	}

	private boolean isResolution(Identifier<IResolution> resolution,
			String resolutionString) {

		return (resolution != null && resolution.getStringIdentifier().equals(
				resolutionString));

	}

	private void processTaskStoryDefectNonRecursionSave(IWorkItem workItem,
			IWorkItem oldState, ISaveParameter saveParameter,
			IParticipantInfoCollector collector, IProgressMonitor monitor)
			throws TeamRepositoryException {

		// Get all work item references (existing and new) as
		// well as the reference to this end point

		IWorkItemReferences workItemExistingReferences = fWorkItemServer
				.resolveWorkItemReferences(workItem, monitor);

		IWorkItemReferences workItemNewReferences = saveParameter
				.getNewReferences();

		IReference workItemEndPoint = IReferenceFactory.INSTANCE
				.createReferenceToItem(workItem);

		// Enforce order of work item closure/reopening based on workflow determined by blocks/depends links
		
		// TODO Uncomment out method call below to deliver 35554: Enforce workflow order
		
		//enforceWorkflowOrder(workItem, oldState, collector, monitor);

		// For defects, block the test task (based on defect
		// type) of any new affected ticket

		if (isDefect(workItem)
				&& saveParameter.getAdditionalSaveParameters() != null
				&& !saveParameter.getAdditionalSaveParameters().contains(
						UPDATE_LINKS)) {

			IWorkItem affectedTicket = getNewAffectedTicket(workItem,
					workItemExistingReferences, workItemNewReferences,
					workItemEndPoint, monitor);

			if (affectedTicket != null) {

				blockTestTaskBasedOnDefectSource(workItem, affectedTicket,
						monitor, collector);
			}

		}

		// If creating a new child story/defect under a ticket,
		// then look to create blocks/depends links

		if (saveParameter.getAdditionalSaveParameters() != null
				&& !saveParameter.getAdditionalSaveParameters().contains(
						UPDATE_LINKS)
				&& (isStory(workItem) || isDefect(workItem))
				&& saveParameter.isCreation()) {

			IWorkItem parentTicket = getNewOrExistingParentWorkItem(workItem,
					workItemExistingReferences, workItemNewReferences,
					workItemEndPoint, monitor);

			if (parentTicket != null && isItRequest(parentTicket)) {

				// Get exemplar story or defect

				IWorkItem exemplarWorkItem = getExemplarStoryOrDefect(
						parentTicket, monitor);

				if (exemplarWorkItem != null) {

					IReference exemplarEndPoint = getEndPointReference(exemplarWorkItem);

					// Get blocked links

					List blocksLinks = getExistingReferences(exemplarWorkItem,
							exemplarEndPoint,
							WorkItemEndPoints.BLOCKS_WORK_ITEM, monitor);

					// Get depends on links

					List dependsOnLinks = getExistingReferences(
							exemplarWorkItem, exemplarEndPoint,
							WorkItemEndPoints.DEPENDS_ON_WORK_ITEM, monitor);

					// Duplicate blocks and depends on links and
					// save

					duplicateWorkflowLinksDefaultToBacklogAndSave(
							exemplarWorkItem, workItem, blocksLinks,
							dependsOnLinks, monitor, collector);

				}

			}

		}

		// If updating status on child story/defect under
		// ticket, then check to see if we need to update the
		// parent ticket status

		if ((isStory(workItem) || isDefect(workItem) || (isTask(workItem) && (isSummary(
				workItem, TASK_NAME_PROD_TEST) || (isSummary(workItem,
				TASK_NAME_IT_SOL_AGREE)))))
				&& isStatusChange(workItem, oldState)) {

			IWorkItem parentTicket = getNewOrExistingParentWorkItem(workItem,
					workItemExistingReferences, workItemNewReferences,
					workItemEndPoint, monitor);

			if (parentTicket != null && isItRequest(parentTicket)) {

				IReference ticketEndPoint = getEndPointReference(parentTicket);

				List ticketExistingReferences = getExistingReferences(
						parentTicket, ticketEndPoint,
						WorkItemEndPoints.CHILD_WORK_ITEMS, monitor);

				updateTicketStatusBasedOnChildren(parentTicket,
						ticketExistingReferences, ticketEndPoint, monitor,
						collector);

				if (isSummary(workItem, TASK_NAME_IT_SOL_AGREE)
						&& isStatusChangeToStatus(workItem, oldState,
								TASK_STATUS_DONE)) {

					renameStoriesAndDefects(parentTicket,
							ticketExistingReferences, ticketEndPoint, monitor,
							collector);

				}
			}

		}

		// Only check upstream work items if work item isn't
		// closed (if it is closed then it isn't relevant to try
		// set the workable date)

		Boolean isWorkItemClosed = isWorkItemClosed(workItem);

		if (!isWorkItemClosed) {

			// Updated workable date based on this work item
			// depending on upstream work items

			updateWorkItemBasedOnUpstream(workItem, workItemExistingReferences,
					workItemNewReferences, workItemEndPoint, monitor, collector);

		}

		// Only check downstream work items if we've got a state
		// change to process or we're not creating as part of a
		// template

		Boolean isWorkflowStateChange = isWorkflowStateChange(workItem,
				oldState);

		Boolean isWorkItemCreatedUsingTemplate = isWorkItemCreatedUsingTemplate(
				workItem, monitor);

		if (isWorkflowStateChange || !isWorkItemCreatedUsingTemplate) {

			// Update workable date on downstream work items
			updateDownstreamWorkItems(workItem, workItemExistingReferences,
					workItemNewReferences, workItemEndPoint, monitor, collector);

		}

		if (isPriorityChange(workItem, oldState)) {

			// Update priority on all children
			updatePriorityChildWorkItems(workItem, workItemExistingReferences,
					workItemNewReferences, workItemEndPoint, monitor, collector);

		}
	}

	private void enforceWorkflowOrder(IWorkItem workItem, IWorkItem oldState,
			IParticipantInfoCollector collector, IProgressMonitor monitor)
			throws TeamRepositoryException {
		// If changing status

		if (isStatusChange(workItem, oldState)) {

			// If closing, check to see if work item is workable

			if (isWorkItemClosed(workItem)) {
				
				Timestamp workableDate = getWorkableDate(workItem, monitor);
				
				if (workableDate == null) {
					
					if (isWorkItemInvalidated(workItem)) {
						
						createError("Cannot invalidate a work item that is not workable i.e. it has open upstream work item(s).",
								"Cannot invalidate work item out of sequence. Use suggest invalidate instead.", collector);
						
					} else {
						
						createError("Cannot close a work item that is not workable i.e. it has open upstream work item(s).",
								"Cannot close work item out of sequence.", collector);
						
					}
	
					
					
				}				

				// If reopening, check to see if any downstream work items are
				// closed (disallow in that case)
			} else if (isBeingReopened(workItem, oldState)) {
				
				if (!isAllBlocksOpen(workItem, monitor)) {
					
					createError("Cannot re-open a work item that has closed downstream work item(s).",
							"Cannot re-open work item out of sequence.", collector);
					
				}

			}

		}
	}

	private void renameStoriesAndDefects(IWorkItem parentTicket,
			List ticketExistingReferences, IReference ticketEndPoint,
			IProgressMonitor monitor, IParticipantInfoCollector collector)
			throws TeamRepositoryException {

		// Get template name

		String templateName = parentTicket.getHTMLSummary().getPlainText();

		int totalToRename = 0;

		List renameWorkItems = new ArrayList();

		// Find stories/defects that still have default name

		for (Iterator iterator = ticketExistingReferences.iterator(); iterator
				.hasNext();) {

			IReference iReference = (IReference) iterator.next();

			// Get link from reference

			ILink link = iReference.getLink();

			IEndPointDescriptor otherEndPointDescriptor = link
					.getOtherEndpointDescriptor(ticketEndPoint);

			if (otherEndPointDescriptor == WorkItemEndPoints.CHILD_WORK_ITEMS) {

				IWorkItem childWorkItem = (IWorkItem) fWorkItemServer
						.getAuditableCommon().resolveAuditable(
								(IWorkItemHandle) link.getOtherRef(
										ticketEndPoint).resolve(),
								IWorkItem.FULL_PROFILE, monitor);

				if (isStoryOrDefect(childWorkItem)) {

					if (childWorkItem.getHTMLSummary().getPlainText()
							.equals(TASK_NAME_DESIGN_AND_DEV_SOL)) {

						totalToRename++;

						renameWorkItems.add(childWorkItem);

					}

				}

			}
		}

		// Rename story if still has default name

		if (totalToRename == 1) {

			renameSummaryAndSave((IWorkItem) renameWorkItems.get(0),
					templateName, monitor, collector);

		} else {

			int workItemUniqueNumber = 0;

			for (Iterator iterator = renameWorkItems.iterator(); iterator
					.hasNext();) {

				workItemUniqueNumber++;

				IWorkItem workItemToRename = (IWorkItem) iterator.next();

				renameSummaryAndSave(workItemToRename, templateName + " ("
						+ workItemUniqueNumber + ")", monitor, collector);
			}

		}

	}

	private void renameSummaryAndSave(IWorkItem workItem, String newSummary,
			IProgressMonitor monitor, IParticipantInfoCollector collector)
			throws TeamRepositoryException {

		IWorkItem workingCopy = (IWorkItem) fWorkItemServer
				.getAuditableCommon()
				.resolveAuditable(workItem, IWorkItem.FULL_PROFILE, monitor)
				.getWorkingCopy();

		workingCopy.setHTMLSummary(XMLString.createFromPlainText(newSummary));

		IStatus saveStatus = fWorkItemServer.saveWorkItem3(workingCopy, null,
				null, UPDATE_SUMMARY_PARAM);

		if (!saveStatus.isOK()) {

			createError(NLS.bind("Unable to update summary.",
					"Unable to save the work item ''{0}''.",
					workItem.getItemId()), "Unable to set work item summary.",
					collector);
		}

	}

	private void updateTicketStatusBasedOnChildren(IWorkItem parentTicket,
			List combinedChildList, IReference workItemEndPoint,
			IProgressMonitor monitor, IParticipantInfoCollector collector)
			throws TeamRepositoryException {

		if (isStatus(parentTicket, TICKET_STATUS_DRAFT)) {

			createError("Cannot change status when parent is still in Draft.",
					"Unable to change status.", collector);

		} else {

			Boolean itSolutionAgreed = false;
			Boolean atLeastStartedProgress = false;
			Boolean allTasksAndDefectsClosed = true;
			Boolean productionTested = false;
			Boolean allPreviousTasksToProdTestClosed = true;

			for (Iterator iterator = combinedChildList.iterator(); iterator
					.hasNext();) {

				IReference iReference = (IReference) iterator.next();

				// Get link from reference

				ILink link = iReference.getLink();

				IEndPointDescriptor otherEndPointDescriptor = link
						.getOtherEndpointDescriptor(workItemEndPoint);

				if (otherEndPointDescriptor == WorkItemEndPoints.CHILD_WORK_ITEMS) {

					IWorkItem childWorkItem = (IWorkItem) fWorkItemServer
							.getAuditableCommon().resolveAuditable(
									(IWorkItemHandle) link.getOtherRef(
											workItemEndPoint).resolve(),
									IWorkItem.FULL_PROFILE, monitor);

					if (isStoryOrDefect(childWorkItem)) {

						if (atLeastStartedProgress == false
								&& (isStatus(childWorkItem,
										STORY_STATUS_IN_PROGRESS)
										|| isStatus(childWorkItem,
												STORY_STATUS_IMPLEMENTED)
										|| isStatus(childWorkItem,
												DEFECT_STATUS_IN_PROGRESS) || isStatus(
											childWorkItem,
											DEFECT_STATUS_RESOLVED))) { // Story
																		// or
																		// defect
																		// in
																		// progress
																		// (or
																		// beyond)

							atLeastStartedProgress = true;

						}

						if (allTasksAndDefectsClosed == true
								&& !isWorkItemClosed(childWorkItem)) {

							allTasksAndDefectsClosed = false;
						}

					} else if (isTask(childWorkItem)) {

						if (isWorkItemClosed(childWorkItem)) {

							if (isSummary(childWorkItem, TASK_NAME_PROD_TEST)) {

								productionTested = true;

							}

							if (isSummary(childWorkItem, TASK_NAME_IT_SOL_AGREE)) {

								itSolutionAgreed = true;

							}

							// A task that is not closed...
						} else {

							if (!isSummary(childWorkItem, TASK_NAME_PROD_TEST)) {

								allPreviousTasksToProdTestClosed = false;
							}

						}
					}
				}
			}

			// Determine if ticket is invalid
			if (isStatus(parentTicket, TICKET_STATUS_INVALID)) {

				// Do nothing

				// Determine if we need to set the status back to submitted
			} else if (!isStatus(parentTicket, TICKET_STATUS_SUBMITTED)
					&& !atLeastStartedProgress && !allTasksAndDefectsClosed
					&& !itSolutionAgreed) {

				updateStatusAndSave(parentTicket, TICKET_STATUS_SUBMITTED,
						UPDATE_STATUS_PARAM, monitor, collector);

				// Determine if we need to set the status to ready for
				// development
			} else if (!isStatus(parentTicket, TICKET_STATUS_READY_FOR_DEV)
					&& !atLeastStartedProgress && !allTasksAndDefectsClosed
					&& itSolutionAgreed) {

				updateStatusAndSave(parentTicket, TICKET_STATUS_READY_FOR_DEV,
						UPDATE_STATUS_PARAM, monitor, collector);

				// Determine if we need to set the status to in progress
			} else if (!isStatus(parentTicket, TICKET_STATUS_IN_PROGRESS)
					&& atLeastStartedProgress && !allTasksAndDefectsClosed
					&& itSolutionAgreed) {

				updateStatusAndSave(parentTicket, TICKET_STATUS_IN_PROGRESS,
						UPDATE_STATUS_PARAM, monitor, collector);

				// Determine if we need to set the status to implemented
			} else if (!isStatus(parentTicket, TICKET_STATUS_IMPLEMENTED)
					&& allTasksAndDefectsClosed && !productionTested) {

				updateStatusAndSave(parentTicket, TICKET_STATUS_IMPLEMENTED,
						UPDATE_STATUS_PARAM, monitor, collector);

				// Determine if we need to set the status to done
			} else if (!isStatus(parentTicket, TICKET_STATUS_DONE)
					&& allPreviousTasksToProdTestClosed && productionTested) {

				// First close all stories/defects

				closeAllStoriesAndDefects(combinedChildList, workItemEndPoint,
						monitor, collector);

				// Then close ticket

				updateStatusAndSave(parentTicket, TICKET_STATUS_DONE,
						UPDATE_STATUS_PARAM, monitor, collector);

			} else if (!isStatus(parentTicket, TICKET_STATUS_DONE)
					&& !allPreviousTasksToProdTestClosed && productionTested) {

				IReportInfo info = collector
						.createInfo(
								"All previous tasks must be completed for this IT Request before completing production test.",
								"Check to see which previous tasks are not closed and close them.");
				info.setSeverity(IProcessReport.ERROR);
				collector.addInfo(info);
			}
		}

	}

	private void closeAllStoriesAndDefects(List combinedChildList,
			IReference workItemEndPoint, IProgressMonitor monitor,
			IParticipantInfoCollector collector) throws TeamRepositoryException {

		for (Iterator iterator = combinedChildList.iterator(); iterator
				.hasNext();) {

			IReference iReference = (IReference) iterator.next();

			// Get link from reference

			ILink link = iReference.getLink();

			IEndPointDescriptor otherEndPointDescriptor = link
					.getOtherEndpointDescriptor(workItemEndPoint);

			if (otherEndPointDescriptor == WorkItemEndPoints.CHILD_WORK_ITEMS) {

				IWorkItem childWorkItem = (IWorkItem) fWorkItemServer
						.getAuditableCommon().resolveAuditable(
								(IWorkItemHandle) link.getOtherRef(
										workItemEndPoint).resolve(),
								IWorkItem.FULL_PROFILE, monitor);

				if (!isWorkItemInvalidated(childWorkItem)) {

					if (isStory(childWorkItem)) {

						if (!isStatus(childWorkItem,
								"com.ibm.team.apt.story.verified")) {

							updateStatusAndSave(childWorkItem,
									"com.ibm.team.apt.story.verified",
									UPDATE_STATUS_PARAM, monitor, collector);

						}

					} else if (isDefect(childWorkItem)) {

						if (!isStatus(childWorkItem,
								"com.ibm.team.workitem.defectWorkflow.state.s4")) {

							updateStatusAndSave(
									childWorkItem,
									"com.ibm.team.workitem.defectWorkflow.state.s4",
									UPDATE_STATUS_PARAM, monitor, collector);

						}

					}

				}

			}
		}

	}

	private boolean isSummary(IWorkItem childWorkItem, String summary) {

		return (childWorkItem.getHTMLSummary().getPlainText().equals(summary));
	}

	private void duplicateWorkflowLinksDefaultToBacklogAndSave(
			IWorkItem oldWorkItem, IWorkItem newWorkItem, List blocksLinks,
			List dependsOnLinks, IProgressMonitor monitor,
			IParticipantInfoCollector collector) throws TeamRepositoryException {

		// Get a working copy of the source work item, and then get its current
		// references (that we will be adding to)

		IWorkItem workingCopy = (IWorkItem) fWorkItemServer
				.getAuditableCommon()
				.resolveAuditable(newWorkItem, IWorkItem.FULL_PROFILE, monitor)
				.getWorkingCopy();

		IReference oldEndPoint = getEndPointReference(oldWorkItem);

		IWorkItemReferences workItemExistingReferences = fWorkItemServer
				.resolveWorkItemReferences(workingCopy, monitor);

		// Iterative through blocks links to be duplicated, and add a duplicate
		// link to the existing references for the source work item

		for (Iterator iterator = blocksLinks.iterator(); iterator.hasNext();) {

			IReference iReference = (IReference) iterator.next();

			// Get link from reference

			ILink link = iReference.getLink();

			IEndPointDescriptor otherEndPointDescriptor = link
					.getOtherEndpointDescriptor(oldEndPoint);

			if (otherEndPointDescriptor == WorkItemEndPoints.BLOCKS_WORK_ITEM) {

				IWorkItem blocksWorkItem = (IWorkItem) fWorkItemServer
						.getAuditableCommon().resolveAuditable(
								(IWorkItemHandle) link.getOtherRef(oldEndPoint)
										.resolve(), IWorkItem.FULL_PROFILE,
								monitor);

				// Create a new reference to the opposite item
				IItemReference reference = IReferenceFactory.INSTANCE
						.createReferenceToItem(blocksWorkItem);

				// Add the new reference using a specific work item end point
				workItemExistingReferences.add(
						WorkItemEndPoints.BLOCKS_WORK_ITEM, reference);
			}
		}

		// Iterative through depends on links to be duplicated, and add a
		// duplicate
		// link to the existing references for the source work item

		for (Iterator iterator = dependsOnLinks.iterator(); iterator.hasNext();) {

			IReference iReference = (IReference) iterator.next();

			// Get link from reference

			ILink link = iReference.getLink();

			IEndPointDescriptor otherEndPointDescriptor = link
					.getOtherEndpointDescriptor(oldEndPoint);

			if (otherEndPointDescriptor == WorkItemEndPoints.DEPENDS_ON_WORK_ITEM) {

				IWorkItem dependsOnWorkItem = (IWorkItem) fWorkItemServer
						.getAuditableCommon().resolveAuditable(
								(IWorkItemHandle) link.getOtherRef(oldEndPoint)
										.resolve(), IWorkItem.FULL_PROFILE,
								monitor);

				// Create a new reference to the opposite item
				IItemReference reference = IReferenceFactory.INSTANCE
						.createReferenceToItem(dependsOnWorkItem);

				// Add the new reference using a specific work item end point
				workItemExistingReferences.add(
						WorkItemEndPoints.DEPENDS_ON_WORK_ITEM, reference);
			}
		}

		IStatus saveStatus = fWorkItemServer.saveWorkItem3(workingCopy,
				workItemExistingReferences, null, UPDATE_LINKS_PARAM);
		if (!saveStatus.isOK()) {
			String description = NLS.bind("Unable to create link.",
					"Unable to save the work item ''{0}''.",
					newWorkItem.getItemId());
			IReportInfo info = collector.createInfo("Unable to create link.",
					description);
			info.setSeverity(IProcessReport.ERROR);
			collector.addInfo(info);
		}

	}

	private IWorkItem getExemplarStoryOrDefect(IWorkItem parentTicket,
			IProgressMonitor monitor) throws TeamRepositoryException {

		IWorkItem exemplarStoryOrDefect = null;

		IReference thisEndPoint = IReferenceFactory.INSTANCE
				.createReferenceToItem(parentTicket);

		List existingChildrenList = getExistingReferences(parentTicket,
				thisEndPoint, WorkItemEndPoints.CHILD_WORK_ITEMS, monitor);

		// Iterate through child items

		for (Iterator iterator = existingChildrenList.iterator(); iterator
				.hasNext();) {

			IReference iReference = (IReference) iterator.next();

			// Get link from reference

			ILink link = iReference.getLink();

			IEndPointDescriptor otherEndPointDescriptor = link
					.getOtherEndpointDescriptor(thisEndPoint);

			if (otherEndPointDescriptor == WorkItemEndPoints.CHILD_WORK_ITEMS) {

				IWorkItem childWorkItem = (IWorkItem) fWorkItemServer
						.getAuditableCommon().resolveAuditable(
								(IWorkItemHandle) link
										.getOtherRef(thisEndPoint).resolve(),
								IWorkItem.FULL_PROFILE, monitor);

				// Only consider template created work items
				if (isWorkItemCreatedUsingTemplate(childWorkItem, monitor)) {

					// Consider defects or stories, but story tasks preference
					// over defect
					if (isDefect(childWorkItem)
							&& exemplarStoryOrDefect == null) {
						exemplarStoryOrDefect = childWorkItem;
					} else if (isStory(childWorkItem)
							&& (exemplarStoryOrDefect == null || (exemplarStoryOrDefect != null && !isStory(exemplarStoryOrDefect)))) {
						exemplarStoryOrDefect = childWorkItem;

					}

				}

			}
		}

		return exemplarStoryOrDefect;
	}

	private void blockTestTaskBasedOnDefectSource(IWorkItem defect,
			IWorkItem affectedTicket, IProgressMonitor monitor,
			IParticipantInfoCollector collector) throws TeamRepositoryException {

		String sourceOfDefect = getAttributeDisplayValue(defect,
				"sourceOfDefect", monitor);

		String taskToBlockSummary = null;

		if (sourceOfDefect.equals("QA Testing")) {

			taskToBlockSummary = "QA Test";

		}

		if (sourceOfDefect.equals("UAT")) {

			taskToBlockSummary = "Business Validation (UAT)";

		}

		if (sourceOfDefect.equals("Production Testing")) {

			taskToBlockSummary = "Prod Test";

		}

		if (taskToBlockSummary != null) {

			IWorkItemReferences workItemExistingReferences = fWorkItemServer
					.resolveWorkItemReferences(affectedTicket, monitor);

			IReference workItemEndPoint = IReferenceFactory.INSTANCE
					.createReferenceToItem(affectedTicket);

			IWorkItem taskToBlock = getChildTemplateTask(affectedTicket,
					taskToBlockSummary, workItemExistingReferences,
					workItemEndPoint, monitor);

			if (taskToBlock != null) {

				Boolean isAlreadyLinked = isAlreadyLinked(defect, taskToBlock,
						WorkItemEndPoints.BLOCKS_WORK_ITEM, monitor, collector);

				if (!isAlreadyLinked) {

					createLinkAndSave(defect, taskToBlock,
							WorkItemEndPoints.BLOCKS_WORK_ITEM, monitor,
							collector);

				}

			} else {

				createError(
						"Defect type is " + sourceOfDefect
								+ " but affected ticket '"
								+ affectedTicket.getId() + "' has no "
								+ taskToBlockSummary + " test task.",
						"Cannot link this defect to ticket '"
								+ affectedTicket.getId() + "'.", collector);
			}

		}
	}

	private Boolean isAlreadyLinked(IWorkItem defect, IWorkItem taskToBlock,
			IEndPointDescriptor endPointDescriptor, IProgressMonitor monitor,
			IParticipantInfoCollector collector) throws TeamRepositoryException {

		Boolean isAlreadyLinked = false;

		// Get all work item references (existing and new)
		// as
		// well as the refernce to this end point

		IWorkItemReferences workItemExistingReferences = fWorkItemServer
				.resolveWorkItemReferences(defect, monitor);

		IReference workItemEndPoint = IReferenceFactory.INSTANCE
				.createReferenceToItem(defect);

		// Narrow down to the blocks links

		List blocksReferences = workItemExistingReferences
				.getReferences(endPointDescriptor);

		// Iterate through blocks items

		for (Iterator iterator = blocksReferences.iterator(); iterator
				.hasNext();) {

			IReference iReference = (IReference) iterator.next();

			// Get link from reference

			ILink link = iReference.getLink();

			IEndPointDescriptor thisEndPointDescriptor = link
					.getThisEndpointDescriptor(workItemEndPoint);
			IEndPointDescriptor otherEndPointDescriptor = link
					.getOtherEndpointDescriptor(workItemEndPoint);

			if (otherEndPointDescriptor == endPointDescriptor) {

				IWorkItem blocksWorkItem = getWorkItemFromReference(iReference,
						workItemEndPoint, monitor);

				if (isSameWorkItem(blocksWorkItem, taskToBlock)) {

					isAlreadyLinked = true;

				}
			}
		}

		return isAlreadyLinked;
	}

	private IWorkItem getChildTemplateTask(IWorkItem affectedTicket,
			String taskToBlockSummary,
			IWorkItemReferences workItemExistingReferences,
			IReference workItemEndPoint, IProgressMonitor monitor)
			throws TeamRepositoryException {

		IWorkItem templateTask = null;

		// Narrow down to the affects plan item links

		List childReferences = workItemExistingReferences
				.getReferences(WorkItemEndPoints.CHILD_WORK_ITEMS);

		// Iterate through child work items

		for (Iterator iterator = childReferences.iterator(); iterator.hasNext();) {

			IReference iReference = (IReference) iterator.next();

			// Get link from reference

			ILink link = iReference.getLink();

			IEndPointDescriptor thisEndPointDescriptor = link
					.getThisEndpointDescriptor(workItemEndPoint);
			IEndPointDescriptor otherEndPointDescriptor = link
					.getOtherEndpointDescriptor(workItemEndPoint);

			if (otherEndPointDescriptor == WorkItemEndPoints.CHILD_WORK_ITEMS) {

				IWorkItem childWorkItem = getWorkItemFromReference(iReference,
						workItemEndPoint, monitor);

				if (isWorkItemCreatedUsingTemplate(childWorkItem, monitor)
						&& childWorkItem.getHTMLSummary().getPlainText()
								.equals(taskToBlockSummary)) {

					templateTask = childWorkItem;

				}
			}
		}

		return templateTask;
	}

	private IWorkItem getNewAffectedTicket(IWorkItem workItem,
			IWorkItemReferences existingReferences,
			IWorkItemReferences newReferences, IReference thisEndPoint,
			IProgressMonitor monitor) throws TeamRepositoryException {

		IWorkItem ticket = null;

		// Narrow down to affects plan item work items

		List combinedAffectedPlanItemsList = narrowAndCombineLists(
				existingReferences, newReferences, thisEndPoint,
				AFFECTS_PLAN_ITEM_ENDPOINT, monitor);

		// Iterate through affects plan item work items

		for (Iterator iterator = combinedAffectedPlanItemsList.iterator(); iterator
				.hasNext();) {

			IReference iReference = (IReference) iterator.next();

			// Use reference to get link and then other endpoint descriptor

			ILink link = iReference.getLink();

			IEndPointDescriptor otherEndPointDescriptor = link
					.getOtherEndpointDescriptor(thisEndPoint);

			if (otherEndPointDescriptor == AFFECTS_PLAN_ITEM_ENDPOINT) {

				IWorkItem affectsPlanItemWorkItem = getWorkItemFromReference(
						iReference, thisEndPoint, monitor);

				if (isItRequest(affectsPlanItemWorkItem)) {

					// For tickets, return the ticket

					ticket = affectsPlanItemWorkItem;

				} else if (isStory(affectsPlanItemWorkItem)) {

					// For stories, need to get the parent ticket to return

					IWorkItem parentWorkItem = getExistingParentWorkItem(
							affectsPlanItemWorkItem, monitor);

					// If the parent is a ticket then return that
					if (isItRequest(parentWorkItem)) {
						ticket = parentWorkItem;
					}

				}
			}
		}

		return ticket;
	}

	private boolean isStatusChangeToStatus(IWorkItem workItem,
			IWorkItem oldState, String toStatus) {

		Boolean isStatusChange = false;

		if (workItem != null && oldState != null) {
			
			String newStatus = workItem.getState2().getStringIdentifier();
			String oldStatus = oldState.getState2().getStringIdentifier();

			if (workItem.getState2().getStringIdentifier().equals(toStatus)
					&& !oldState.getState2().getStringIdentifier()
							.equals(toStatus)) {
				isStatusChange = true;
			}

		}

		return isStatusChange;
	}

	private boolean isStatusChange(IWorkItem workItem, IWorkItem oldState) {

		Boolean isStatusChange = false;

		if (workItem != null && oldState != null) {

			if (!workItem.getState2().getStringIdentifier()
					.equals(oldState.getState2().getStringIdentifier())) {
				isStatusChange = true;
			}

		}

		return isStatusChange;
	}

	private boolean isBeingReopened(IWorkItem workItem, IWorkItem oldState)
			throws TeamRepositoryException {

		// Being reopened if it was closed and now it is open (i.e. not closed)

		return (isWorkItemClosed(oldState) && !isWorkItemClosed(workItem));
	}

	private void updatePriorityChildWorkItems(IWorkItem workItem,
			IWorkItemReferences existingReferences,
			IWorkItemReferences newReferences, IReference thisEndPoint,
			IProgressMonitor monitor, IParticipantInfoCollector collector)
			throws TeamRepositoryException {

		// Narrow down to the blocks work items

		List existingChildrenList = existingReferences
				.getReferences(WorkItemEndPoints.CHILD_WORK_ITEMS);

		List newChildrenList = newReferences
				.getReferences(WorkItemEndPoints.CHILD_WORK_ITEMS);

		List combinedChildrenList = combineReferenceLists(existingChildrenList,
				newChildrenList, thisEndPoint, monitor);

		// Iterate through blocked work items

		for (Iterator iterator = combinedChildrenList.iterator(); iterator
				.hasNext();) {

			IReference iReference = (IReference) iterator.next();

			// Get link from reference

			ILink link = iReference.getLink();

			IEndPointDescriptor thisEndPointDescriptor = link
					.getThisEndpointDescriptor(thisEndPoint);
			IEndPointDescriptor otherEndPointDescriptor = link
					.getOtherEndpointDescriptor(thisEndPoint);

			if (otherEndPointDescriptor == WorkItemEndPoints.CHILD_WORK_ITEMS) {

				IWorkItem childWorkItem = getWorkItemFromReference(iReference,
						thisEndPoint, monitor);

				if (isTaskStoryOrDefect(childWorkItem)) {

					// Save work item

					updatePriorityAndSave(childWorkItem,
							workItem.getPriority(), monitor, collector);

				}

			}
		}

	}

	private void updatePriorityAndSave(IWorkItem workItem,
			Identifier<IPriority> priority, IProgressMonitor monitor,
			IParticipantInfoCollector collector) throws TeamRepositoryException {

		IWorkItem workingCopy = (IWorkItem) fWorkItemServer
				.getAuditableCommon()
				.resolveAuditable(workItem, IWorkItem.FULL_PROFILE, monitor)
				.getWorkingCopy();

		workingCopy.setPriority(priority);

		IStatus saveStatus = fWorkItemServer.saveWorkItem3(workingCopy, null,
				null, UPDATE_PRIORITY_PARAM);

		if (!saveStatus.isOK()) {
			String description = NLS.bind("Unable to set work item priority.",
					"Unable to save the work item ''{0}''.",
					workItem.getItemId());
			IReportInfo info = collector.createInfo(
					"Unable to set work item priority.", description);
			info.setSeverity(IProcessReport.ERROR);
			collector.addInfo(info);
		}

	}

	private void updateChildrenBasedOnUpstream(IWorkItem workItem,
			IWorkItemReferences existingReferences,
			IWorkItemReferences newReferences, IReference thisEndPoint,
			IProgressMonitor monitor, IParticipantInfoCollector collector)
			throws TeamRepositoryException {

		// Narrow down to children work items

		List existingChildrenList = existingReferences
				.getReferences(WorkItemEndPoints.CHILD_WORK_ITEMS);

		List newChildrenList = newReferences
				.getReferences(WorkItemEndPoints.CHILD_WORK_ITEMS);

		List combinedChildrenList = combineReferenceLists(existingChildrenList,
				newChildrenList, thisEndPoint, monitor);

		String parent = workItem.getHTMLSummary().getPlainText();

		// Iterate through children work items

		for (Iterator iterator = combinedChildrenList.iterator(); iterator
				.hasNext();) {

			IReference iReference = (IReference) iterator.next();

			// Get link from reference

			ILink link = iReference.getLink();

			IEndPointDescriptor thisEndPointDescriptor = link
					.getThisEndpointDescriptor(thisEndPoint);
			IEndPointDescriptor otherEndPointDescriptor = link
					.getOtherEndpointDescriptor(thisEndPoint);

			if (otherEndPointDescriptor == WorkItemEndPoints.CHILD_WORK_ITEMS) {

				IWorkItem childWorkItem = getWorkItemFromReference(iReference,
						thisEndPoint, monitor);

				if (isTaskStoryOrDefect(childWorkItem)) {

					String child = childWorkItem.getHTMLSummary()
							.getPlainText();

					// Get all work item references (existing and new) as
					// well as the refernce to this end point

					IWorkItemReferences childWorkItemExistingReferences = fWorkItemServer
							.resolveWorkItemReferences(childWorkItem, monitor);

					IReference childWorkItemEndPoint = IReferenceFactory.INSTANCE
							.createReferenceToItem(childWorkItem);

					updateWorkItemBasedOnUpstream(childWorkItem,
							childWorkItemExistingReferences, null,
							childWorkItemEndPoint, monitor, collector);
				}
			}
		}

	}

	private void updateWorkItemBasedOnUpstream(IWorkItem workItem,
			IWorkItemReferences existingReferences,
			IWorkItemReferences newReferences, IReference thisEndPoint,
			IProgressMonitor monitor, IParticipantInfoCollector collector)
			throws TeamRepositoryException {

		// Narrow down to the depends on work items

		List existingDependsOnList = existingReferences
				.getReferences(WorkItemEndPoints.DEPENDS_ON_WORK_ITEM);

		List newDependsOnList = null;

		if (newReferences != null) {
			newDependsOnList = newReferences
					.getReferences(WorkItemEndPoints.DEPENDS_ON_WORK_ITEM);
		}

		List combinedDependsOnList = combineReferenceLists(
				existingDependsOnList, newDependsOnList, thisEndPoint, monitor);

		Boolean noOpenDependsOn = true;

		// Iterate through depends on work items

		for (Iterator iterator = combinedDependsOnList.iterator(); iterator
				.hasNext();) {

			IReference iReference = (IReference) iterator.next();

			// Get link from reference

			ILink link = iReference.getLink();

			IEndPointDescriptor thisEndPointDescriptor = link
					.getThisEndpointDescriptor(thisEndPoint);
			IEndPointDescriptor otherEndPointDescriptor = link
					.getOtherEndpointDescriptor(thisEndPoint);

			if (otherEndPointDescriptor == WorkItemEndPoints.DEPENDS_ON_WORK_ITEM) {

				IWorkItem dependsOnWorkItem = getWorkItemFromReference(
						iReference, thisEndPoint, monitor);

				if (isTaskStoryOrDefect(dependsOnWorkItem)) {

					Boolean isWorkItemClosed = isWorkItemClosed(dependsOnWorkItem);

					if (!isWorkItemClosed) {

						noOpenDependsOn = false;

					}

				}
			}

		}

		Timestamp workableDate = getWorkableDate(workItem, monitor);

		IWorkItem parent = getExistingParentWorkItem(workItem, monitor);

		Timestamp parentWorkableDate = null;

		if (parent != null) {

			parentWorkableDate = getWorkableDate(parent, monitor);

		}

		// If workable date is set and either there is an open dependency or
		// there is not an open dependency but the ticket is in draft mode, then
		// unset the date
		if ((!noOpenDependsOn && workableDate != null)
				|| (noOpenDependsOn && workableDate != null && ((isDraftParent(
						workItem, existingReferences, newReferences,
						thisEndPoint, monitor, collector)
						&& parent != null && isItRequest(parent)) || (parent != null
						&& parentWorkableDate == null && isTask(parent))))) {

			if (isWorkItemClosed(workItem)) {

				// TODO: Potentially throw error here and disallow change

				IReportInfo createProblemInfo = collector
						.createInfo(
								"Work item is already completed and so change is not allowed.",
								"To go ahead with this change you must first reopen the work item.");
				collector.addInfo(createProblemInfo);

			} else {

				// Unset workable date

				workableDate = null;

				// Save work item

				updateWorkableDateAndSave(workItem,

				workableDate, monitor, collector);

			}

			// If no open dependency and date not set and ticket is not in draft
			// then set date
		} else if (noOpenDependsOn
				&& workableDate == null
				&& (!isDraftParent(workItem, existingReferences, newReferences,
						thisEndPoint, monitor, collector) && parent != null && isItRequest(parent))
				|| (parent != null && parentWorkableDate != null && isTask(parent))) {

			// Get current timestamp

			Timestamp currentTimestamp = new Timestamp(
					System.currentTimeMillis());

			// Set workable date

			workableDate = currentTimestamp;

			// Save work item

			updateWorkableDateAndSave(workItem, workableDate, monitor,
					collector);
		}

	}

	private Boolean isDraftParent(IWorkItem workItem,
			IWorkItemReferences existingReferences,
			IWorkItemReferences newReferences, IReference thisEndPoint,
			IProgressMonitor monitor, IParticipantInfoCollector collector)
			throws TeamRepositoryException {

		Boolean isDraftParent = false;

		// Narrow down to the parent work items

		List existingParentList = existingReferences
				.getReferences(WorkItemEndPoints.PARENT_WORK_ITEM);

		List newParentList = null;

		if (newReferences != null) {
			newParentList = newReferences
					.getReferences(WorkItemEndPoints.PARENT_WORK_ITEM);
		}

		List combinedParentList = combineReferenceLists(existingParentList,
				newParentList, thisEndPoint, monitor);

		// Iterate through parent work items (there should only be maximum one)

		for (Iterator iterator = combinedParentList.iterator(); iterator
				.hasNext();) {

			IReference iReference = (IReference) iterator.next();

			// Get link from reference

			ILink link = iReference.getLink();

			IEndPointDescriptor thisEndPointDescriptor = link
					.getThisEndpointDescriptor(thisEndPoint);
			IEndPointDescriptor otherEndPointDescriptor = link
					.getOtherEndpointDescriptor(thisEndPoint);

			if (otherEndPointDescriptor == WorkItemEndPoints.PARENT_WORK_ITEM) {

				IWorkItem parentWorkItem = getWorkItemFromReference(iReference,
						thisEndPoint, monitor);

				if (parentWorkItem
						.getState2()
						.getStringIdentifier()
						.equals(TICKET_STATUS_DRAFT)) {

					isDraftParent = true;
				}
			}
		}

		return isDraftParent;

	}

	private void setNullWorkableDateToCreationDateAndSave(IWorkItem workItem,
			IProgressMonitor monitor, IParticipantInfoCollector collector)
			throws TeamRepositoryException {

		Timestamp workableDate = getWorkableDate(workItem, monitor);

		if (workableDate == null) {

			// Get creation timestamp

			Timestamp creationTimestamp = workItem.getCreationDate();

			// Set workable date

			workableDate = creationTimestamp;

			// Save work item

			updateWorkableDateAndSave(workItem, workableDate, monitor,
					collector);

		}

	}

	private void updateDownstreamWorkItems(IWorkItem workItem,
			IWorkItemReferences existingReferences,
			IWorkItemReferences newReferences, IReference thisEndPoint,
			IProgressMonitor monitor, IParticipantInfoCollector collector)
			throws TeamRepositoryException {

		// Narrow down to the blocks work items

		List existingBlocksList = existingReferences
				.getReferences(WorkItemEndPoints.BLOCKS_WORK_ITEM);

		List newBlocksList = newReferences
				.getReferences(WorkItemEndPoints.BLOCKS_WORK_ITEM);

		List combinedBlocksList = combineReferenceLists(existingBlocksList,
				newBlocksList, thisEndPoint, monitor);

		// Iterate through blocked work items

		for (Iterator iterator = combinedBlocksList.iterator(); iterator
				.hasNext();) {

			IReference iReference = (IReference) iterator.next();

			// Get link from reference

			ILink link = iReference.getLink();

			IEndPointDescriptor thisEndPointDescriptor = link
					.getThisEndpointDescriptor(thisEndPoint);
			IEndPointDescriptor otherEndPointDescriptor = link
					.getOtherEndpointDescriptor(thisEndPoint);

			if (otherEndPointDescriptor == WorkItemEndPoints.BLOCKS_WORK_ITEM) {

				IWorkItem blockedWorkItem = getWorkItemFromReference(
						iReference, thisEndPoint, monitor);

				if (isTaskStoryOrDefect(blockedWorkItem)) {

					Timestamp workableDate = getWorkableDate(blockedWorkItem,
							monitor);

					// Check if work item is closed

					Boolean isDependsOnWorkItemClosed = isWorkItemClosed(workItem);

					// If status is done and no workable date
					// currently set then we'll set the workable
					// date
					if (isDependsOnWorkItemClosed && workableDate == null) {

						// Only make update if there are no other open upstream
						// work items

						if (isAllDependsOnClosed(blockedWorkItem, monitor)) {

							// Get current timestamp

							Timestamp currentTimestamp = new Timestamp(
									System.currentTimeMillis());

							// Set workable date

							workableDate = currentTimestamp;

							// Save work item

							updateWorkableDateAndSave(blockedWorkItem,
									workableDate, monitor, collector);

						}

					} else

					// If status is not closed and workable date
					// is set then we need to clear workable date
					if (!isDependsOnWorkItemClosed && workableDate != null) {

						// Unset workable date

						workableDate = null;

						// Save work item

						updateWorkableDateAndSave(blockedWorkItem,

						workableDate, monitor, collector);
					}
				}

			}
		}

	}

	private void updateWorkableDateAndSave(IWorkItem workItem,
			Timestamp workableDateTimestamp, IProgressMonitor monitor,
			IParticipantInfoCollector collector) throws TeamRepositoryException {

		IAttribute workableDateAttribute = fWorkItemCommon.findAttribute(
				workItem.getProjectArea(), "workableDate", monitor);

		IAttribute isWorkableAttribute = fWorkItemCommon.findAttribute(
				workItem.getProjectArea(), "isWorkable", monitor);

		// Always first check if attributes exist
		if (workableDateAttribute != null && isWorkableAttribute != null
				&& workItem.hasAttribute(workableDateAttribute)
				&& workItem.hasAttribute(isWorkableAttribute)) {

			IWorkItem workingCopy = (IWorkItem) fWorkItemServer
					.getAuditableCommon()
					.resolveAuditable(workItem, IWorkItem.FULL_PROFILE, monitor)
					.getWorkingCopy();

			workingCopy.setValue(workableDateAttribute, workableDateTimestamp);
			workingCopy.setValue(isWorkableAttribute,
					(workableDateTimestamp != null));

			IStatus saveStatus = fWorkItemServer.saveWorkItem3(workingCopy,
					null, null, UPDATE_WORKABLE_DATE_PARAM);
			if (!saveStatus.isOK()) {
				String description = NLS.bind(
						"Unable to set the workable date.",
						"Unable to save the work item ''{0}''.",
						workItem.getItemId());
				IReportInfo info = collector.createInfo(
						"Unable to set the workable date.", description);
				info.setSeverity(IProcessReport.ERROR);
				collector.addInfo(info);
			}

		}

	}

	private void createLinkAndSave(IWorkItem defect, IWorkItem taskToBlock,
			IEndPointDescriptor endPointDescriptor, IProgressMonitor monitor,
			IParticipantInfoCollector collector) throws TeamRepositoryException {

		IWorkItem workingCopy = (IWorkItem) fWorkItemServer
				.getAuditableCommon()
				.resolveAuditable(defect, IWorkItem.FULL_PROFILE, monitor)
				.getWorkingCopy();

		// Create a new reference to the opposite item
		IItemReference reference = IReferenceFactory.INSTANCE
				.createReferenceToItem(taskToBlock);

		IWorkItemReferences workItemExistingReferences = fWorkItemServer
				.resolveWorkItemReferences(workingCopy, monitor);

		// Add the new reference using a specific work item end point
		workItemExistingReferences.add(endPointDescriptor, reference);

		IStatus saveStatus = fWorkItemServer.saveWorkItem3(workingCopy,
				workItemExistingReferences, null, UPDATE_LINKS_PARAM);
		if (!saveStatus.isOK()) {
			String description = NLS
					.bind("Unable to create link.",
							"Unable to save the work item ''{0}''.",
							defect.getItemId());
			IReportInfo info = collector.createInfo("Unable to create link.",
					description);
			info.setSeverity(IProcessReport.ERROR);
			collector.addInfo(info);
		}

	}

	private class ParsedConfig {
		public String fAgileProjectArea = null;
		public String fAgileCategoryRoot = null;
		public ArrayList<DomainCategoryMapping> fDomainCategoryMappings = new ArrayList<DomainCategoryMapping>();
	}

	private class DomainCategoryMapping {
		public String fPrimaryDomain = null;
		public String fWISubcategory = null;

		DomainCategoryMapping(String primaryDomain, String wiSubcategory) {
			fPrimaryDomain = primaryDomain;
			fWISubcategory = wiSubcategory;
		}

	}

	private void parseConfig(IProcessConfigurationElement participantConfig,
			ParsedConfig parsedConfig) {

		IProcessConfigurationElement[] participantConfigElements = participantConfig
				.getChildren();

		for (IProcessConfigurationElement element : participantConfigElements) {
			if (element.getName().equals("agileProjectArea")) {

				parsedConfig.fAgileProjectArea = element.getCharacterData();

			} else if (element.getName().equals("agileCategoryRoot")) {

				parsedConfig.fAgileCategoryRoot = element.getCharacterData();

			} else if (element.getName().equals("domainCategoryMapping")) {

				IProcessConfigurationElement domainCategoryMappingConfig = element;

				IProcessConfigurationElement[] childElements = domainCategoryMappingConfig
						.getChildren();

				String primaryDomain = null;
				String wiSubcategory = null;

				for (IProcessConfigurationElement childElement : childElements) {
					if (childElement.getName().equals("primaryDomain")) {
						primaryDomain = childElement.getCharacterData();
					} else if (childElement.getName().equals("wiSubcategory")) {
						wiSubcategory = childElement.getCharacterData();
					}
				}

				parsedConfig.fDomainCategoryMappings
						.add(new DomainCategoryMapping(primaryDomain,
								wiSubcategory));

			}
		}
	}
}
