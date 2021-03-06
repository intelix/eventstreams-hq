/*
 * Copyright 2014-15 Intelix Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

define(['react', 'core_mixin', 'common_statelabel', 'common_rate', 'common_yesno', 'common_button_startstop', 'common_button_delete', './ReplayButton'],
    function (React, core_mixin, StateLabel, Rate, YesNo, StartStopButton, DeleteButton, ReplayButton) {

        return React.createClass({
            mixins: [core_mixin],

            componentName: function () {
                return "app/content/gates/TableRow/" + this.props.ckey;
            },

            subscriptionConfig: function (props) {
                return [
                    {address: props.addr, route: props.ckey, topic: 'info', dataKey: 'info'},
                    {address: props.addr, route: props.ckey, topic: 'stats', dataKey: 'stats'}
                ];
            },
            getInitialState: function () {
                return {info: false, stats: false}
            },

            renderData: function () {
                var self = this;

                var info = self.state.info;
                var stats = self.state.stats;


                var replayButton;
                if (info.replaySupported) {
                    replayButton = <ReplayButton {...self.props} enabled={info.state == 'active' || info.state == 'error'} />;
                } else {
                    replayButton = "";
                }

                var EditLink = require('common_link_edit');

                return (
                    <tr ref='monitorVisibility' >
                        <td><EditLink editEvent="editGate" text={info.name} {...this.props} /></td>
                        <td>{info.address}</td>
                        <td>{stats.inflight}</td>
                        <td><Rate value={stats.rate} /></td>
                        <td><Rate value={stats.mrate} /></td>
                        <td>{stats.activeDS}</td>
                        <td>{info.sinks}</td>
                        <td><StateLabel state={info.state} details={info.stateDetails} /></td>
                        <td>
                            <StartStopButton {...this.props} state={info.state}  />
                            <DeleteButton {...this.props} />
                            {replayButton}
                        </td>
                    </tr>
                );


            },
            renderLoading: function () {
                return (
                    <tr>
                        <td colSpan="100%">Loading...</td>
                    </tr>
                );
            },

            render: function () {
                if (this.state.info) {
                    return this.renderData();
                } else {
                    return this.renderLoading();
                }
            }
        });

    });