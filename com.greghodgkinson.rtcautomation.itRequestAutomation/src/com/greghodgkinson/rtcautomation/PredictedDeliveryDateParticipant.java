package com.greghodgkinson.rtcautomation;

import java.net.URI;
import java.sql.Date;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
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

public class PredictedDeliveryDateParticipant extends
		RtcAutomationAbstractService implements IOperationParticipant {

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

				// Only care about updates
				if (!saveParameter.isCreation()) {

					// If there is no recursion...

					if (additionalSaveParams == null
							|| (additionalSaveParams != null
									&& !additionalSaveParams
											.contains(UPDATE_WORKABLE_DATE)
									&& !additionalSaveParams
											.contains(UPDATE_SUMMARY)
									&& !additionalSaveParams
											.contains(UPDATE_LINKS)
									&& !additionalSaveParams
											.contains(UPDATE_PRIORITY)
									&& !additionalSaveParams
											.contains(CREATE_CLONE) && !(additionalSaveParams
									.contains(CASCADE_INVALIDATE) && additionalSaveParams
									.contains("wi=" + workItem.getId())))) {

						IWorkItem oldState = (IWorkItem) saveParameter
								.getOldState();
						
						Boolean isItRequest = isItRequest(workItem);

						// If sla category is being set on an it request
						if (isItRequest && isSlaCategoryBeingSet(workItem, oldState,
								monitor)) {

							Timestamp itSolAgreeWorkableDate = getChildWorkableDate(
									workItem, TASK_NAME_IT_SOL_AGREE, monitor);

							// And it solution agreement task has a workable
							// date
							if (itSolAgreeWorkableDate != null) {

								// Work out the predicted delivery date
								Timestamp predictedDeliveryDate = predictDeliveryDate(
										getAttributeDisplayValue(workItem,
												"slaCategory", monitor),
										itSolAgreeWorkableDate,
										participantConfig);

								// Set the predicted delivery date
								updatePredictedDeliveryDateAndSave(workItem,
										predictedDeliveryDate, monitor,
										collector);

							}

							// If the sla category is being unset
						} else if (isItRequest && isSlaCategoryBeingUnset(workItem, oldState,
								monitor)) {

							// Get the predicted delivery date
							Timestamp currentPredictedDeliveryDate = getAttributeTimestamp(
									workItem, "slaPredictedDeliveryDate",
									monitor);

							// If date currently set
							if (currentPredictedDeliveryDate != null) {

								// Unset the predicted delivery date
								updatePredictedDeliveryDateAndSave(workItem,
										null, monitor, collector);

							}
						}

						// Else if workable date save...

					} else if (saveParameter.getAdditionalSaveParameters() != null
							&& saveParameter.getAdditionalSaveParameters()
									.contains(UPDATE_WORKABLE_DATE)) {

						// If it solution agreement task that is having its
						// workable
						// date saved
						if (workItem.getHTMLSummary().getPlainText().equals(
								TASK_NAME_IT_SOL_AGREE)) {

							IWorkItem ticket = getParent(workItem, monitor);

							// Get current predicted delivery date
							Timestamp currentPredictedDeliveryDate = getAttributeTimestamp(
									ticket, "slaPredictedDeliveryDate", monitor);

							// Work out the predicted delivery date
							Timestamp predictedDeliveryDate = predictDeliveryDate(
									getAttributeDisplayValue(ticket,
											"slaCategory", monitor),
									getWorkableDate(workItem, monitor),
									participantConfig);

							if (!equalTimestamp(currentPredictedDeliveryDate,
									predictedDeliveryDate)) {

								updatePredictedDeliveryDateAndSave(ticket,
										predictedDeliveryDate, monitor,
										collector);
							}

						}

					}

				}
			}
		}

	}
	
	private boolean equalTimestamp(Timestamp ts1, Timestamp ts2) {
		
		if (ts1 == null && ts2 != null || ts1 != null && ts2 == null)
			return false;
		
	    return(ts1.equals(ts2));
	
	}

	private boolean isSlaCategoryBeingUnset(IWorkItem workItem,
			IWorkItem oldState, IProgressMonitor monitor) throws TeamRepositoryException {
		
		return (isAttributeBeingUnset(workItem, oldState,
				"slaCategory", monitor) || (isAttributeEqualTo(workItem, "slaCategory", "Unassigned", monitor) && isAttributeBeingSet(workItem, oldState, "slaCategory", monitor)));
	
	}
	
	private boolean isSlaCategoryBeingSet(IWorkItem workItem,
			IWorkItem oldState, IProgressMonitor monitor) throws TeamRepositoryException {
		
		return (isAttributeBeingSet(workItem, oldState,
				"slaCategory", monitor) && !isAttributeEqualTo(workItem, "slaCategory", "Unassigned", monitor));
	
	}

	private boolean equal(String string1, String string2) {

		return ((string1 == null && string2 == null) || (string1 != null && string1
				.equals(string2)));
	}

	private void updatePredictedDeliveryDateAndSave(IWorkItem workItem,
			Timestamp predictedDeliveryDate, IProgressMonitor monitor,
			IParticipantInfoCollector collector) throws TeamRepositoryException {

		IAttribute predictedDeliveryDateAttribute = fWorkItemCommon
				.findAttribute(workItem.getProjectArea(),
						"slaPredictedDeliveryDate", monitor);

		// Always first check if attributes exist
		if (predictedDeliveryDateAttribute != null
				&& workItem.hasAttribute(predictedDeliveryDateAttribute)) {

			IWorkItem workingCopy = (IWorkItem) fWorkItemServer
					.getAuditableCommon()
					.resolveAuditable(workItem, IWorkItem.FULL_PROFILE, monitor)
					.getWorkingCopy();

			workingCopy.setValue(predictedDeliveryDateAttribute,
					predictedDeliveryDate);

			IStatus saveStatus = fWorkItemServer.saveWorkItem3(workingCopy,
					null, null, UPDATE_PRD_DELIVERY_DATE_PARAM);
			if (!saveStatus.isOK()) {

				createError("Unable to set the predicted delivery date.",
						"Unable to save the work item.", collector);
			}

		}

	}

	private Timestamp predictDeliveryDate(String slaCategory,
			Timestamp itSolAgreeWorkableDate,
			IProcessConfigurationElement participantConfig) {

		Timestamp predictedDeliveryDate = null;

		if (slaCategory == null || itSolAgreeWorkableDate == null) {
			predictedDeliveryDate = null;

		} else {

			// Get the configuration which helps map the sla category to a
			// number of days to add

			ParsedConfig parsedConfig = new ParsedConfig();

			parseConfig(participantConfig, parsedConfig);

			Integer daysToAdd = 0;

			for (Iterator iterator = parsedConfig.fSlaCategoryMappings
					.iterator(); iterator.hasNext();) {

				SlaCategoryMapping mapping = (SlaCategoryMapping) iterator
						.next();

				if (mapping.fSlaCategory.equals(slaCategory)) {

					daysToAdd = Integer.parseInt(mapping.fDays);

				}
			}

			// Add the number of days to the it sol agreement workable date to
			// give us the predicted delivery date

			Calendar cal = Calendar.getInstance();
			cal.setTime(itSolAgreeWorkableDate);
			cal.add(Calendar.DAY_OF_WEEK, daysToAdd);

			// Return the calculated date

			predictedDeliveryDate = new Timestamp(cal.getTime().getTime());

		}

		return predictedDeliveryDate;

	}

	private class ParsedConfig {
		public ArrayList<SlaCategoryMapping> fSlaCategoryMappings = new ArrayList<SlaCategoryMapping>();
	}

	private class SlaCategoryMapping {
		public String fSlaCategory = null;
		public String fDays = null;

		SlaCategoryMapping(String slaCategory, String days) {
			fSlaCategory = slaCategory;
			fDays = days;
		}

	}

	private void parseConfig(IProcessConfigurationElement participantConfig,
			ParsedConfig parsedConfig) {

		IProcessConfigurationElement[] participantConfigElements = participantConfig
				.getChildren();

		for (IProcessConfigurationElement element : participantConfigElements) {
			if (element.getName().equals("slaCategoryMapping")) {

				IProcessConfigurationElement slaCategoryMappingConfig = element;

				IProcessConfigurationElement[] childElements = slaCategoryMappingConfig
						.getChildren();

				String slaCategory = null;
				String days = null;

				for (IProcessConfigurationElement childElement : childElements) {
					if (childElement.getName().equals("slaCategory")) {
						slaCategory = childElement.getCharacterData();
					} else if (childElement.getName().equals("days")) {
						days = childElement.getCharacterData();
					}
				}

				parsedConfig.fSlaCategoryMappings.add(new SlaCategoryMapping(
						slaCategory, days));

			}
		}
	}

}
