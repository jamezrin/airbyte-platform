import intersection from "lodash/intersection";
import { useCallback, useRef } from "react";
import { FieldErrors, set, useFormContext, useFormState } from "react-hook-form";
import { BaseSchema } from "yup";

import {
  BuilderView,
  useConnectorBuilderFormManagementState,
} from "services/connectorBuilder/ConnectorBuilderStateService";

import {
  BuilderFormValues,
  BuilderState,
  DeclarativeOAuthAuthenticatorType,
  extractInterpolatedConfigKey,
} from "./types";
import { useBuilderValidationSchema } from "./useBuilderValidationSchema";

export const useBuilderErrors = () => {
  const { getValues } = useFormContext<BuilderState>();
  const { errors } = useFormState<BuilderState>();
  const formValuesErrors: FieldErrors<BuilderFormValues> = errors.formValues ?? {};
  const { setScrollToField } = useConnectorBuilderFormManagementState();
  const { setValue } = useFormContext();
  const { globalSchema, streamSchema, builderFormValidationSchema } = useBuilderValidationSchema();

  const errorsRef = useRef(formValuesErrors);
  errorsRef.current = formValuesErrors;

  const invalidViews = useCallback((limitToViews?: BuilderView[], inputErrors?: FieldErrors<BuilderFormValues>) => {
    const errorsToCheck = inputErrors !== undefined ? inputErrors : errorsRef.current;
    const errorKeys = Object.keys(errorsToCheck).filter((errorKey) =>
      Boolean(errorsToCheck[errorKey as keyof BuilderFormValues])
    );

    const invalidViews: BuilderView[] = [];

    if (errorKeys.includes("global")) {
      invalidViews.push("global");
    }

    if (errorKeys.includes("streams") && typeof errorsToCheck.streams === "object") {
      const errorStreamNums = Object.keys(errorsToCheck.streams ?? {}).filter((errorKey) =>
        Boolean(errorsToCheck.streams?.[Number(errorKey)])
      );

      invalidViews.push(...errorStreamNums.map((numString) => Number(numString)));
    }

    return limitToViews === undefined ? invalidViews : intersection(invalidViews, limitToViews);
  }, []);

  // Returns true if the global config fields or any stream config fields have errors in the provided rhf errors, and false otherwise.
  // If limitToViews is provided, the error check is limited to only those views.
  const hasErrors = useCallback(
    (limitToViews?: BuilderView[]) => {
      return invalidViews(limitToViews).length > 0;
    },
    [invalidViews]
  );

  const validateAndTouch = useCallback(
    (callback?: () => void, limitToViews?: BuilderView[]) => {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const getFirstErrorPath = (value: any, schema: BaseSchema): string | undefined => {
        if (value?.authenticator?.type === DeclarativeOAuthAuthenticatorType) {
          const testingValues = getValues("testingValues") ?? {};

          const tokenType: "refresh" | "access" = !!value.authenticator.refresh_token_updater ? "refresh" : "access";
          const tokenConfigKey = extractInterpolatedConfigKey(
            tokenType === "refresh" ? value.authenticator.refresh_token : value.authenticator.access_token_value
          );

          if (!(tokenConfigKey in testingValues)) {
            return "authenticator.declarative_oauth_flow";
          }
        }

        try {
          schema.validateSync(value);
        } catch (e) {
          return e.path;
        }
        return undefined;
      };

      if (limitToViews) {
        for (const view of limitToViews) {
          if (view === "global") {
            // cast to any to avoid "Type instantiation is excessively deep and possibly infinite" error
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            const firstErrorPath = getFirstErrorPath(getValues("formValues.global") as any, globalSchema);
            if (firstErrorPath) {
              setScrollToField(`formValues.global.${firstErrorPath}`);
              setValue("view", view);
              return;
            }
          }
          if (typeof view === "number") {
            const firstErrorPath = getFirstErrorPath(getValues(`formValues.streams.${view}`), streamSchema);
            if (firstErrorPath) {
              setScrollToField(`formValues.streams.${view}.${firstErrorPath}`);
              setValue("view", view);
              return;
            }
          }
        }
      } else {
        const firstErrorPath = getFirstErrorPath(getValues("formValues"), builderFormValidationSchema);
        if (!firstErrorPath) {
          return;
        }
        const errorObject = {};
        set(errorObject, firstErrorPath, "error");
        const invalidBuilderViews = invalidViews(limitToViews, errorObject);
        if (invalidBuilderViews.length > 0) {
          setScrollToField(`formValues.${firstErrorPath}`);
          setValue("view", invalidBuilderViews[0]);
          return;
        }
      }

      callback?.();
    },
    [builderFormValidationSchema, getValues, globalSchema, invalidViews, setScrollToField, setValue, streamSchema]
  );

  return { hasErrors, validateAndTouch };
};
