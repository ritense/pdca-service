import React from 'react';
import '../shared/styles';
import { mountBundle } from '../shared/PdcaRoot';
import { EvaluationPanel } from './EvaluationPanel';

// The side panel takes the host theme's layer, like GZAC's own panel.
mountBundle(<EvaluationPanel />, 'pdca-panel-theme');
