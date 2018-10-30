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

public class CascadeResolutionParticipant extends RtcAutomationAbstractService
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

				if (additionalSaveParams == null
						|| (additionalSaveParams != null
								&& !additionalSaveParams
										.contains(UPDATE_WORKABLE_DATE)
								&& !additionalSaveParams
										.contains(UPDATE_SUMMARY)
								&& !additionalSaveParams.contains(UPDATE_LINKS)
								&& !additionalSaveParams
										.contains(UPDATE_PRIORITY)
								&& !additionalSaveParams.contains(CREATE_CLONE) && !(additionalSaveParams
								.contains(CASCADE_INVALIDATE) && additionalSaveParams
								.contains("wi=" + workItem.getId())))) {

					IWorkItem oldState = (IWorkItem) saveParameter
							.getOldState();

					// If an update and is being resolved

					if (oldState != null
							&& isBeingResolved(workItem, oldState, monitor)) {

						if (isWorkItemInvalidated(workItem)) {

							invalidateAllChildren(
									workItem,
									saveParameter.getAdditionalSaveParameters(),
									monitor, collector);

						} else {

							errorIfAnyChildUnresolved(workItem, monitor,
									collector);

						}

					} else if (oldState != null
							&& isBeingReopened(workItem, oldState, monitor)) {

						errorIfParentResolved(workItem, monitor, collector);

					}
				}
			}
		}

	}

	private Boolean hasChildren(IWorkItem workItem, IProgressMonitor monitor)
			throws TeamRepositoryException {

		List existingChildren = getExistingReferences(workItem,
				WorkItemEndPoints.CHILD_WORK_ITEMS, monitor);

		return (existingChildren.size() > 0);

	}

	private void errorIfAnyChildUnresolved(IWorkItem workItem,
			IProgressMonitor monitor, IParticipantInfoCollector collector)
			throws TeamRepositoryException {

		List existingChildren = getExistingReferences(workItem,
				WorkItemEndPoints.CHILD_WORK_ITEMS, monitor);

		// Iterate through children work items

		for (Iterator iterator = existingChildren.iterator(); iterator
				.hasNext();) {

			IReference iReference = (IReference) iterator.next();

			// Get link from reference

			ILink link = iReference.getLink();

			IWorkItem childWorkItem = getWorkItemFromReference(iReference,
					workItem, monitor);

			if (!isResolved(childWorkItem, monitor)) {

				createError(
						"Cannot resolve work item '" + workItem.getId()
								+ "' because its child work item '"
								+ childWorkItem.getId() + "' is unresolved.",
						"Child work item '" + childWorkItem.getId()
								+ "' is unresolved.", collector);

			}
		}
	}

	private void errorIfParentResolved(IWorkItem workItem,
			IProgressMonitor monitor, IParticipantInfoCollector collector)
			throws TeamRepositoryException {

		List existingChildren = getExistingReferences(workItem,
				WorkItemEndPoints.PARENT_WORK_ITEM, monitor);

		// Iterate through parent work items (there should only be one)

		for (Iterator iterator = existingChildren.iterator(); iterator
				.hasNext();) {

			IReference iReference = (IReference) iterator.next();

			// Get link from reference

			ILink link = iReference.getLink();

			IWorkItem parentWorkItem = getWorkItemFromReference(iReference,
					workItem, monitor);

			if (isResolved(parentWorkItem, monitor)) {

				createError("Parent work item '" + parentWorkItem.getId()
						+ "' is resolved.", "Cannot save work item '"
						+ workItem.getId() + "' because its parent work item '"
						+ parentWorkItem.getId() + "' is resolved.", collector);

			}
		}
	}

	private void invalidateAllChildren(IWorkItem workItem,
			Set<String> additionalSaveParams, IProgressMonitor monitor,
			IParticipantInfoCollector collector) throws TeamRepositoryException {

		Set<String> newAdditionalSaveParams = (Set<String>) new HashSet<String>();

		if (additionalSaveParams != null) {

			newAdditionalSaveParams.addAll(additionalSaveParams);

		}

		// Add UPDATE_STATUS param

		addAdditionalSaveParam(newAdditionalSaveParams, CASCADE_INVALIDATE);

		// Add parent (this) work item id so that we can avoid loop recursion

		addAdditionalSaveParam(newAdditionalSaveParams,
				"wi=" + workItem.getId());

		List existingChildren = getExistingReferences(workItem,
				WorkItemEndPoints.CHILD_WORK_ITEMS, monitor);

		// Iterate through children work items

		for (Iterator iterator = existingChildren.iterator(); iterator
				.hasNext();) {

			IReference iReference = (IReference) iterator.next();

			// Get link from reference

			ILink link = iReference.getLink();

			IWorkItem childWorkItem = getWorkItemFromReference(iReference,
					workItem, monitor);
			
			String summary=childWorkItem.getHTMLSummary().getPlainText();

			invalidateWorkItem(childWorkItem, newAdditionalSaveParams, monitor,
					collector);

		}

	}

	private void addAdditionalSaveParam(Set<String> additionalSaveParams,
			String newParam) {

		if (!additionalSaveParams.contains(newParam)) {

			additionalSaveParams.add(newParam);

		}
	}

	private void invalidateWorkItem(IWorkItem workItem,
			Set<String> additionalSaveParams, IProgressMonitor monitor,
			IParticipantInfoCollector collector) throws TeamRepositoryException {

		if (isItRequest(workItem)) {

			if (!isResolved(workItem, monitor)) {

				updateStatusAndSave(workItem, TICKET_STATUS_INVALID,
						additionalSaveParams, monitor, collector);
			}

		} else if (isStory(workItem)) {

			if (!isResolved(workItem, monitor)) {

				updateStatusAndSave(workItem, STORY_STATUS_INVALID,
						additionalSaveParams, monitor, collector);
			}

		} else if (isTask(workItem)) {

			if (!isResolved(workItem, monitor)) {

				updateStatusAndSave(workItem, TASK_STATUS_INVALID,
						additionalSaveParams, monitor, collector);
			}

		}
	}

	private Boolean isBeingResolved(IWorkItem workItem, IWorkItem oldState,
			IProgressMonitor monitor) throws TeamRepositoryException {

		int newStateGroup = getStateGroup(workItem, monitor);

		int oldStateGroup = getStateGroup(oldState, monitor);

		return (newStateGroup != oldStateGroup && newStateGroup == IWorkflowInfo.CLOSED_STATES);

	}

	private Boolean isBeingReopened(IWorkItem workItem, IWorkItem oldState,
			IProgressMonitor monitor) throws TeamRepositoryException {

		int newStateGroup = getStateGroup(workItem, monitor);

		int oldStateGroup = getStateGroup(oldState, monitor);

		return (newStateGroup != oldStateGroup && oldStateGroup == IWorkflowInfo.CLOSED_STATES);

	}

	private int getStateGroup(IWorkItem workItem, IProgressMonitor monitor)
			throws TeamRepositoryException {

		IWorkflowInfo info = fWorkItemCommon
				.findWorkflowInfo(workItem, monitor);

		return (info.getStateGroup(workItem.getState2()));
	}

	private Boolean isResolved(IWorkItem workItem, IProgressMonitor monitor)
			throws TeamRepositoryException {

		int stateGroup = getStateGroup(workItem, monitor);

		return (stateGroup == IWorkflowInfo.CLOSED_STATES);
	}

}
