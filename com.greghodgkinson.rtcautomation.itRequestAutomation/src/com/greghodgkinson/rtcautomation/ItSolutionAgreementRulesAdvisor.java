package com.greghodgkinson.rtcautomation;

import java.sql.Timestamp;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;

import com.ibm.team.links.common.IItemReference;
import com.ibm.team.links.common.ILink;
import com.ibm.team.links.common.IReference;
import com.ibm.team.links.common.factory.IReferenceFactory;
import com.ibm.team.process.common.IDevelopmentLine;
import com.ibm.team.process.common.IIterationHandle;
import com.ibm.team.process.common.IProcessConfigurationElement;
import com.ibm.team.process.common.IProjectAreaHandle;
import com.ibm.team.process.common.advice.AdvisableOperation;
import com.ibm.team.process.common.advice.IAdvisorInfo;
import com.ibm.team.process.common.advice.runtime.IOperationParticipant;
import com.ibm.team.process.common.advice.runtime.IParticipantInfoCollector;
import com.ibm.team.repository.common.TeamRepositoryException;
import com.ibm.team.repository.service.AbstractService;
import com.ibm.team.workitem.common.ISaveParameter;
import com.ibm.team.workitem.common.IWorkItemCommon;
import com.ibm.team.workitem.common.internal.model.WorkItem;
import com.ibm.team.workitem.common.model.IApproval;
import com.ibm.team.workitem.common.model.IApprovals;
import com.ibm.team.workitem.common.model.IAttribute;
import com.ibm.team.workitem.common.model.ICategoryHandle;
import com.ibm.team.workitem.common.model.ILiteral;
import com.ibm.team.workitem.common.model.IWorkItem;
import com.ibm.team.workitem.common.model.IWorkItemHandle;
import com.ibm.team.workitem.common.model.IWorkItemReferences;
import com.ibm.team.workitem.common.model.WorkItemEndPoints;
import com.ibm.team.workitem.service.IWorkItemServer;

import com.ibm.team.process.common.advice.IAdvisorInfoCollector;
import com.ibm.team.process.common.advice.runtime.IOperationAdvisor;
import com.ibm.team.process.internal.common.DevelopmentLine;
import com.ibm.team.repository.common.IAuditable;
import com.ibm.team.workitem.common.model.IState;
import com.ibm.team.workitem.common.model.Identifier;

public class ItSolutionAgreementRulesAdvisor extends
		RtcAutomationAbstractService implements IOperationAdvisor {

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

				String newType = workItem.getWorkItemType();
				Identifier<IState> newStatus = workItem.getState2();

				// Only continue if type is task and status is done

				if ((newType.equals("task"))
						&& (newStatus.getStringIdentifier()
								.equals("com.ibm.team.workitem.taskWorkflow.state.s3"))) {

					String newSummary = workItem.getHTMLSummary()
							.getPlainText();

					// Only continue if summary is 'IT Solution Agreement'

					if (newSummary.equals("IT Solution Agreement")) {

						// Get the required service interfaces
						fWorkItemServer = getService(IWorkItemServer.class);
						fWorkItemCommon = getService(IWorkItemCommon.class);

						// Continue if has parent

						IWorkItemHandle parentHandle = findParentHandle(
								saveParameter, monitor);
						if (parentHandle != null) {

							// Get the full state of the parent work item

							IWorkItem parent = (IWorkItem) fWorkItemServer
									.getAuditableCommon()
									.resolveAuditable(parentHandle,
											IWorkItem.FULL_PROFILE, monitor)
									.getWorkingCopy();

							Identifier<IState> parentStatus = parent
									.getState2();

							// Rule: Need an approval for other tickets before completing solution agreement and moving to ready for development
							
							// Get ticket value stream
							
							IAttribute valueStreamAttribute = fWorkItemCommon
									.findAttribute(parent.getProjectArea(),
											"valueStreamAttribute", monitor);
							Object valueStreamValue = parent
									.getValue(valueStreamAttribute);

							if (valueStreamValue instanceof Identifier) {
								Identifier valueStreamLiteralID = (Identifier) valueStreamValue;
								ILiteral valueStreamLiteral = getLiteralbyID(
										valueStreamLiteralID, valueStreamAttribute);

								String valueStreamDisplayValue = valueStreamLiteral
										.getName();

								if (valueStreamDisplayValue.equals("Other")) {

									// Get approvals

									IApprovals approvals = parent.getApprovals();

									final List<IApproval> approvalList = approvals
											.getContents();

									// Look for at least one approved approval

									final Iterator<IApproval> it = approvalList
											.iterator();

									boolean approvedApproval = false;

									while (it.hasNext() && !approvedApproval) {

										IApproval approval = it.next();

										String approvalState = approval
												.getDescriptor()
												.getCumulativeStateIdentifier()
												.toString();

										// If approval is in state of approved

										if (approvalState
												.equals("com.ibm.team.workitem.approvalState.approved")) {

											approvedApproval = true;

										}

									}

									// Rule: Other tickets require an approval before completing solution agreement

									if (!approvedApproval) {

										IAdvisorInfo createProblemInfo = collector
												.createProblemInfo(
														"The parent work item needs a completed (i.e. Approved) approval. This is required before you can mark this task as 'Done'.",
														"To finalize the IT Solution Agreement you must have an approved approval on the parent IT Request.",
														"error");
										collector.addInfo(createProblemInfo);

									}
								}

							}
							
							// If other, then check for approval
							
							// If no approval then error
							

							// Rule: Primary application must be set on parent
							// ticket

							IAttribute primaryApplicationAttribute = fWorkItemCommon
									.findAttribute(parent.getProjectArea(),
											"primaryApplication", monitor);
							Object primaryApplicationValue = parent
									.getValue(primaryApplicationAttribute);

							if (primaryApplicationValue instanceof Identifier) {
								Identifier primaryApplicationLiteralID = (Identifier) primaryApplicationValue;
								ILiteral primaryApplicationLiteral = getLiteralbyID(
										primaryApplicationLiteralID,
										primaryApplicationAttribute);

								String primaryApplicationDisplayValue = primaryApplicationLiteral
										.getName();

								if (primaryApplicationDisplayValue
										.equals("Unassigned")) {

									IAdvisorInfo createProblemInfo = collector
											.createProblemInfo(
													"The parent work item's Primary Application must be set to something other than 'Unassigned'. This is required before you can mark this task as 'Done'.",
													"To finalize the IT Solution Agreement you must set the Primary Application on the parent IT Request.",
													"error");
									collector.addInfo(createProblemInfo);

								}

							}

							// Rule: SLA category must be set on parent ticket

							IAttribute slaCategoryAttribute = fWorkItemCommon
									.findAttribute(parent.getProjectArea(),
											"slaCategory", monitor);
							Object slaCategoryValue = parent
									.getValue(slaCategoryAttribute);

							if (slaCategoryValue instanceof Identifier) {
								Identifier slaCategoryLiteralID = (Identifier) slaCategoryValue;
								ILiteral slaCategoryLiteral = getLiteralbyID(
										slaCategoryLiteralID,
										slaCategoryAttribute);

								String slaCategoryDisplayValue = slaCategoryLiteral
										.getName();

								if (slaCategoryDisplayValue
										.equals("Unassigned")) {

									IAdvisorInfo createProblemInfo = collector
											.createProblemInfo(
													"The parent work item's SLA Category must be set to something other than 'Unassigned'. This is required before you can mark this task as 'Done'.",
													"To finalize the IT Solution Agreement you must set the SLA Category on the parent IT Request.",
													"error");
									collector.addInfo(createProblemInfo);

								}

							}

							// Get all work item references

							IWorkItemReferences references = fWorkItemServer
									.resolveWorkItemReferences(parentHandle,
											monitor);

							// Narrow down to the children

							List listChildReferences = references
									.getReferences(WorkItemEndPoints.CHILD_WORK_ITEMS);

							// Iterate through children

							IReference parentEndpoint = IReferenceFactory.INSTANCE
									.createReferenceToItem(parentHandle);

							for (Iterator iterator = listChildReferences
									.iterator(); iterator.hasNext();) {

								IReference iReference = (IReference) iterator
										.next();

								ILink link = iReference.getLink();

								if (link.getOtherEndpointDescriptor(parentEndpoint) == WorkItemEndPoints.CHILD_WORK_ITEMS) {

									IWorkItem child = (IWorkItem) fWorkItemServer
											.getAuditableCommon()
											.resolveAuditable(
													(IWorkItemHandle) link
															.getOtherRef(
																	parentEndpoint)
															.resolve(),
													IWorkItem.FULL_PROFILE,
													monitor);

									String childType = child.getWorkItemType();

									// If child is a story

									if (childType
											.equals("com.ibm.team.apt.workItemType.story")) {

										ICategoryHandle storyCategoryHandle = child
												.getCategory();

										String storyTeam = fWorkItemCommon
												.resolveHierarchicalName(
														storyCategoryHandle,
														monitor);

										IIterationHandle storyIterationHandle = child
												.getTarget();

										// Rule: Error if team is still set to
										// default development (i.e. has not
										// been assigned to a specific team)

										if (storyTeam
												.equals("Team/Development")) {

											IAdvisorInfo createProblemInfo = collector
													.createProblemInfo(
															"A story under the parent IT Request is filed against 'Team/Development'. File all stories against a specific development team before you mark this task as 'Done'.",
															"To finalize the IT Solution Agreement you must set the filed against attribute to a specific development team on all stories associated with the parent IT Request.",
															"error");
											collector
													.addInfo(createProblemInfo);

										}

									}

									// If child is a task

									if (childType.equals("task")) {

										String taskSummary = child
												.getHTMLSummary()
												.getPlainText();

										// If summary is "Prod Test"

										if (taskSummary.equals("Prod Test")) {

											// Get due date

											Timestamp taskDueDate = child
													.getDueDate();

											// Rule: Error if due date not set
											// on prod test task

											if (taskDueDate == null) {

												IAdvisorInfo createProblemInfo = collector
														.createProblemInfo(
																"Due date not set on Prod Test task. This is required before you can mark this task as 'Done'.",
																"To finalize the IT Solution Agreement you must set the due date on the Prod Test task associated with the parent IT Request.",
																"error");
												collector
														.addInfo(createProblemInfo);

											}
										}

									}

								}
							}

						}

					}

				}

			}

		}

	}

}
