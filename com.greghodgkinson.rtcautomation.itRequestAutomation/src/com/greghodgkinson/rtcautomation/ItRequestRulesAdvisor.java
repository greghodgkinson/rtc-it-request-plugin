package com.greghodgkinson.rtcautomation;

import java.util.Iterator;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;

import com.ibm.team.links.common.ILink;
import com.ibm.team.links.common.IReference;
import com.ibm.team.links.common.factory.IReferenceFactory;
import com.ibm.team.links.common.registry.IEndPointDescriptor;
import com.ibm.team.process.common.IProcessConfigurationElement;
import com.ibm.team.process.common.advice.AdvisableOperation;
import com.ibm.team.process.common.advice.IAdvisorInfo;
import com.ibm.team.repository.common.TeamRepositoryException;
import com.ibm.team.workitem.common.ISaveParameter;
import com.ibm.team.workitem.common.IWorkItemCommon;
import com.ibm.team.workitem.common.model.IApproval;
import com.ibm.team.workitem.common.model.IApprovals;
import com.ibm.team.workitem.common.model.IAttribute;
import com.ibm.team.workitem.common.model.ILiteral;
import com.ibm.team.workitem.common.model.IWorkItem;
import com.ibm.team.workitem.common.model.IWorkItemHandle;
import com.ibm.team.workitem.common.model.WorkItemEndPoints;
import com.ibm.team.workitem.service.IWorkItemServer;

import com.ibm.team.process.common.advice.IAdvisorInfoCollector;
import com.ibm.team.process.common.advice.runtime.IOperationAdvisor;
import com.ibm.team.repository.common.IAuditable;
import com.ibm.team.workitem.common.model.IState;
import com.ibm.team.workitem.common.model.Identifier;

public class ItRequestRulesAdvisor extends RtcAutomationAbstractService
		implements IOperationAdvisor {

	public void run(AdvisableOperation operation,
			IProcessConfigurationElement advisorConfiguration,
			IAdvisorInfoCollector collector, IProgressMonitor monitor)
			throws TeamRepositoryException {

		Object data = operation.getOperationData();
		ISaveParameter saveParameter = null;

		// Don't continue if operation is not a save

		if (data instanceof ISaveParameter) {

			saveParameter = (ISaveParameter) data;
			IAuditable auditable = saveParameter.getNewState();

			// Don't continue if auditable is not a work item

			if (auditable instanceof IWorkItem) {

				IWorkItem workItem = (IWorkItem) auditable;

				String wiType = workItem.getWorkItemType();

				// Only continue if type is IT Request

				if (wiType.equals(TICKET_WI_TYPE)) {

					// Get the required service interfaces

					fWorkItemServer = getService(IWorkItemServer.class);
					fWorkItemCommon = getService(IWorkItemCommon.class);

					IWorkItem oldState = (IWorkItem) saveParameter
							.getOldState();

					// If changed from a previous work item type

					if (changedFromNonTicketType(workItem, oldState)) {
						createError(
								"Work item may not be changed into an IT request.",
								"Refresh to discard change.", collector);

					}

					// If child links are being removed

					List deletedChildrenList = saveParameter.getNewReferences()
							.getDeletedReferences(
									WorkItemEndPoints.CHILD_WORK_ITEMS);

					if (!deletedChildrenList.isEmpty()) {

						// Do not allow any work items created by a template
						// to be unlinked

						doNotAllowTemplateChildrenToBeUnlinked(
								deletedChildrenList, workItem, collector,
								monitor);

					}
				}

			}
		}

	}

	private Boolean changedFromNonTicketType(IWorkItem workItem,
			IWorkItem oldState) {

		Boolean returnVal = false;

		if (oldState != null) {

			String wiType = workItem.getWorkItemType();
			String previousWiType = oldState.getWorkItemType();

			if (wiType.equals(TICKET_WI_TYPE)
					&& !previousWiType.equals(TICKET_WI_TYPE)) {
				returnVal = true;
			}

		}

		return returnVal;
	}

	private void doNotAllowTemplateChildrenToBeUnlinked(List deletedLinks,
			IWorkItem workItem, IAdvisorInfoCollector collector,
			IProgressMonitor monitor) throws TeamRepositoryException {

		IReference thisEndPoint = IReferenceFactory.INSTANCE
				.createReferenceToItem(workItem);

		// Iterate through depends on work items

		for (Iterator iterator = deletedLinks.iterator(); iterator.hasNext();) {

			IReference iReference = (IReference) iterator.next();

			// Get link from reference

			ILink link = iReference.getLink();

			IEndPointDescriptor thisEndPointDescriptor = link
					.getThisEndpointDescriptor(thisEndPoint);
			IEndPointDescriptor otherEndPointDescriptor = link
					.getOtherEndpointDescriptor(thisEndPoint);

			if (otherEndPointDescriptor == WorkItemEndPoints.CHILD_WORK_ITEMS) {

				IWorkItem childWorkItem = (IWorkItem) fWorkItemServer
						.getAuditableCommon().resolveAuditable(
								(IWorkItemHandle) link
										.getOtherRef(thisEndPoint).resolve(),
								IWorkItem.FULL_PROFILE, monitor);

				if (isWorkItemCreatedUsingTemplate(childWorkItem, monitor)) {

					// Rule 2: Work items created by the template may not be
					// unlinked

					createError(
							"Template work items links may not be removed/deleted.",
							"Refresh to discard link deletions.", collector);

				}
			}
		}

	}

	private void createError(String errorDescription, String errorInfo,
			IAdvisorInfoCollector collector) {
		IAdvisorInfo createProblemInfo = collector.createProblemInfo(
				errorDescription, errorInfo, "error");
		collector.addInfo(createProblemInfo);
	}

}
